#!/usr/bin/env python3
"""
Builds the offline building map (footprints plus heights) from OpenStreetMap, for the 3D map view.

Source: Geofabrik's daily Colorado extract of OpenStreetMap
  https://download.geofabrik.de/north-america/us/colorado.html
  Map data (c) OpenStreetMap contributors, available under the Open Database License.

Only the area inside BBOX is packed. It defaults to a small example area (central Denver); set BBOX to your own town, or pass
west,south,east,north in the BUILDINGS_BBOX environment variable; widen it to cover more
of Colorado. Tiles are the same zoom-10 tiles as the streets, so the app can reuse its tile math.

Output:
  assets/buildings/<x>_<y>.bin   (little-endian)
    u32 building count, then each building:
      u8  height in half meters (so 0..127 m)
      u16 point count (an open ring: the closing point is not repeated)
      i32 first x, i32 first y   tile-local, 256 units per zoom-10 pixel (about 0.47 m per unit)
      then (count - 1) pairs of i16 deltas
    A building is stored once, in the tile that holds its center.
  assets/buildings/index.json

Heights: the `height` tag if there is one, else `building:levels` x 3.2 m, else a guess from the
building type and footprint size. Guessed heights vary a little so a street does not look cloned.

Usage: python3 tools/fetch_buildings.py [output_dir] [input.osm.pbf]
"""
import os as _os
_REGION = _os.environ.get('MAP_REGION', 'north-america/us/colorado')   # Geofabrik path of your state or country
import json, math, os, struct, sys, urllib.request
from collections import defaultdict

import numpy as np
import osmium
import shapely
from shapely import wkb

Z = 10
N = 2 ** Z * 256
# your town. Widen this to cover more of Colorado.
import os as _os
_b = _os.environ.get('BUILDINGS_BBOX', '-105.05,39.68,-104.90,39.80').split(',')
BBOX = dict(west=float(_b[0]), south=float(_b[1]), east=float(_b[2]), north=float(_b[3]))
PBF_URL = 'https://download.geofabrik.de/' + _REGION + '-latest.osm.pbf'

SIMPLIFY_PX = 0.004     # about 0.5 m at zoom 10 in Colorado
MIN_AREA_M2 = 15.0      # drop sheds and ruins
MAX_POINTS = 600        # skip absurdly detailed outlines
UNITS = 256             # tile-local units per zoom-10 pixel

LOW = {'house', 'detached', 'semidetached_house', 'residential', 'bungalow', 'terrace', 'static_caravan',
       'cabin', 'houseboat'}
TINY = {'garage', 'garages', 'carport', 'shed', 'hut', 'roof', 'greenhouse', 'barn', 'stable', 'service',
        'kiosk', 'container', 'farm_auxiliary', 'storage_tank', 'silo', 'cowshed', 'sty', 'allotment_house'}
BY_TYPE = {'apartments': 12, 'dormitory': 10, 'hotel': 12, 'office': 9, 'commercial': 7, 'retail': 6,
           'supermarket': 6, 'industrial': 8, 'warehouse': 8, 'factory': 9, 'manufacture': 9,
           'church': 9, 'cathedral': 14, 'chapel': 6, 'mosque': 9, 'synagogue': 9, 'temple': 9,
           'school': 8, 'university': 10, 'college': 10, 'hospital': 12, 'civic': 9, 'public': 9,
           'government': 9, 'fire_station': 7, 'train_station': 7, 'transportation': 7, 'parking': 8,
           'stadium': 14, 'sports_hall': 9, 'hangar': 10}


def meters_per_px(lat):
    return 40075016.686 * math.cos(math.radians(lat)) / N


def to_px(coords):
    """lon/lat array -> zoom-10 global pixel coordinates (Web Mercator)."""
    lon, lat = coords[:, 0], np.clip(coords[:, 1], -85, 85)
    x = (lon + 180) / 360 * N
    r = np.radians(lat)
    y = (1 - np.log(np.tan(r) + 1 / np.cos(r)) / np.pi) / 2 * N
    return np.column_stack([x, y])


def tile_x(lon):
    return int((lon + 180) / 360 * 2 ** Z)


def tile_y(lat):
    r = math.radians(lat)
    return int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * 2 ** Z)


def parse_meters(raw):
    """'12', '12 m', '40 ft', "40'" -> meters, or None."""
    raw = (raw or '').strip().lower().replace(',', '.')
    num = ''
    for ch in raw:
        if ch.isdigit() or (ch == '.' and '.' not in num):
            num += ch
        elif num:
            break
    try:
        v = float(num)
    except ValueError:
        return None
    if 'ft' in raw or "'" in raw or 'feet' in raw:
        v *= 0.3048
    return v if 0.5 < v < 600 else None


def jitter(x, y):
    """A stable value in 0..1 for a spot, so guessed heights do not change between builds."""
    return (math.sin(x * 12.9898 + y * 78.233) * 43758.5453) % 1.0


def height_for(tags, area_m2, cx, cy):
    h = parse_meters(tags.get('height'))
    if h:
        return h, True
    try:
        levels = float((tags.get('building:levels') or '').split(';')[0].strip())
    except ValueError:
        levels = 0
    if 0 < levels < 200:
        roof = 1.0 if tags.get('roof:levels') not in (None, '', '0') else 0.0
        return max(3.0, levels * 3.2 + roof), True
    kind = (tags.get('building') or 'yes').lower()
    if kind in TINY:
        base = 3.0
    elif kind in LOW:
        base = 5.0 if area_m2 < 400 else 6.5
    elif kind in BY_TYPE:
        base = BY_TYPE[kind]
    elif area_m2 < 60:
        base = 3.0
    elif area_m2 < 250:
        base = 4.8
    elif area_m2 < 1000:
        base = 6.5
    elif area_m2 < 5000:
        base = 8.0
    else:
        base = 9.5
    return base * (0.88 + 0.24 * jitter(cx, cy)), False


def download(path):
    if os.path.exists(path):
        return
    print('downloading', PBF_URL)
    urllib.request.urlretrieve(PBF_URL, path + '.part')
    os.replace(path + '.part', path)


def encode_tile(blds, tx, ty):
    out = bytearray(struct.pack('<I', len(blds)))
    for h, ring in blds:
        q = np.round((ring - (tx * 256, ty * 256)) * UNITS).astype(np.int64)
        pts = [tuple(q[0])]
        for a, b in zip(q[:-1], q[1:]):
            steps = int(max(abs(b[0] - a[0]), abs(b[1] - a[1])) // 30000) + 1
            for s in range(1, steps + 1):
                pts.append((a[0] + (b[0] - a[0]) * s // steps, a[1] + (b[1] - a[1]) * s // steps))
        out += struct.pack('<BHii', int(min(255, max(4, round(h * 2)))), len(pts), int(pts[0][0]), int(pts[0][1]))
        for (ax, ay), (bx, by) in zip(pts[:-1], pts[1:]):
            out += struct.pack('<hh', int(bx - ax), int(by - ay))
    return bytes(out)


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/assets/buildings'
    pbf = sys.argv[2] if len(sys.argv) > 2 else '/tmp/' + _REGION.split('/')[-1] + '-latest.osm.pbf'
    download(pbf)
    os.makedirs(out_dir, exist_ok=True)
    x0, x1 = tile_x(BBOX['west']), tile_x(BBOX['east'])
    y0, y1 = tile_y(BBOX['north']), tile_y(BBOX['south'])
    mpp2 = meters_per_px((BBOX['north'] + BBOX['south']) / 2) ** 2
    fab = osmium.geom.WKBFactory()
    tiles = defaultdict(list)
    stats = defaultdict(int)

    fp = (osmium.FileProcessor(pbf)
          .with_locations()
          .with_areas(osmium.filter.KeyFilter('building'))
          .with_filter(osmium.filter.KeyFilter('building')))
    for obj in fp:
        if not obj.is_area():
            continue
        tags = obj.tags
        b = tags.get('building')
        if not b or b == 'no':
            continue
        try:
            g = wkb.loads(fab.create_multipolygon(obj), hex=True)
            g = shapely.transform(g, to_px)
            cxp, cyp = g.centroid.x, g.centroid.y
            if not (x0 * 256 <= cxp < (x1 + 1) * 256 and y0 * 256 <= cyp < (y1 + 1) * 256):
                continue
            lon = (cxp / N) * 360 - 180
            lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * cyp / N))))
            if not (BBOX['west'] <= lon <= BBOX['east'] and BBOX['south'] <= lat <= BBOX['north']):
                continue
            for poly in getattr(g, 'geoms', [g]):
                if poly.geom_type != 'Polygon':
                    continue
                area = poly.area * mpp2
                if area < MIN_AREA_M2:
                    stats['tiny'] += 1
                    continue
                poly = poly.simplify(SIMPLIFY_PX, preserve_topology=True)
                if poly.is_empty or poly.geom_type != 'Polygon':
                    continue
                ring = np.asarray(poly.exterior.coords)[:-1]
                if len(ring) < 3 or len(ring) > MAX_POINTS:
                    continue
                if shapely.is_ccw(poly.exterior):   # one winding for every building
                    ring = ring[::-1]
                h, tagged = height_for(tags, area, poly.centroid.x, poly.centroid.y)
                tx, ty = int(poly.centroid.x // 256), int(poly.centroid.y // 256)
                tiles[(tx, ty)].append((h, ring))
                stats['tagged_height' if tagged else 'guessed_height'] += 1
        except Exception:
            stats['skipped'] += 1

    total = 0
    for (tx, ty), blds in tiles.items():
        data = encode_tile(blds, tx, ty)
        with open(os.path.join(out_dir, '%d_%d.bin' % (tx, ty)), 'wb') as f:
            f.write(data)
        total += len(data)
    with open(os.path.join(out_dir, 'index.json'), 'w') as f:
        json.dump({'v': 1, 'z': Z, 'x0': x0, 'x1': x1, 'y0': y0, 'y1': y1, 'units': UNITS,
                   'bbox': [BBOX['west'], BBOX['south'], BBOX['east'], BBOX['north']]}, f)
    print('buildings: %d tiles, %.2f MB, %s' % (len(tiles), total / 1e6, dict(stats)))


if __name__ == '__main__':
    main()
