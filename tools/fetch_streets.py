#!/usr/bin/env python3
"""
Builds the offline road and water map for Colorado from OpenStreetMap.

Source: Geofabrik's daily Colorado extract of OpenStreetMap
  https://download.geofabrik.de/north-america/us/colorado.html
  Map data © OpenStreetMap contributors, available under the Open Database License.

Output, one file per zoom-10 tile (the same tiles as the terrain):
  assets/streets/<x>_<y>.bin   (little-endian)
    u16 string count, then each string: u8 byte length + UTF-8 bytes
    u32 feature count, then each feature:
      u8 kind, u16 name index (0xFFFF = none), u16 part count,
      each part: u16 point count, first point as two u16, then int16 deltas
    Coordinates are tile-local, 0..65535 across the 256-pixel tile (about 0.5 m).
    Lines: each part is a line. Water areas: parts are rings, filled even-odd.
  assets/streets/index.json

Kinds: 1 motorway, 2 trunk, 3 primary, 4 secondary, 5 tertiary,
       6 local street, 7 track (dirt/4x4), 20 river, 30 water area

Also writes assets/streets/pois.json, points of interest for all of Colorado:
  {"v": 1, "p": [[kind, lat, lon, name, elevation_ft_or_null], ...]}
  kinds: 1 peak, 2 mountain pass, 3 trailhead, 4 campground, 5 gas station

Usage: python3 tools/fetch_streets.py [output_dir] [input.osm.pbf]
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
_bb = _os.environ.get('MAP_BBOX', '-109.3,36.8,-101.8,41.2').split(',')   # west,south,east,north of your area
BBOX = dict(west=float(_bb[0]), south=float(_bb[1]), east=float(_bb[2]), north=float(_bb[3]))
PBF_URL = 'https://download.geofabrik.de/' + _REGION + '-latest.osm.pbf'

ROAD = {
    'motorway': 1, 'motorway_link': 1, 'trunk': 2, 'trunk_link': 2,
    'primary': 3, 'primary_link': 3, 'secondary': 4, 'secondary_link': 4,
    'tertiary': 5, 'tertiary_link': 5,
    'residential': 6, 'unclassified': 6, 'living_street': 6, 'road': 6,
    'track': 7,
}
RIVER, WATER = 20, 30
PEAK, PASS, TRAILHEAD, CAMP, FUEL = 1, 2, 3, 4, 5


def poi_kind(tags):
    if tags.get('natural') == 'peak' or tags.get('natural') == 'volcano':
        return PEAK
    if tags.get('mountain_pass') == 'yes' or (tags.get('natural') == 'saddle' and tags.get('name')):
        return PASS
    if tags.get('highway') == 'trailhead':
        return TRAILHEAD
    if tags.get('tourism') == 'camp_site':
        return CAMP
    if tags.get('amenity') == 'fuel':
        return FUEL
    return None


def elevation_ft(tags):
    """OSM 'ele' is meters unless marked as feet; returns feet or None."""
    raw = (tags.get('ele') or '').strip().lower().replace(',', '')
    num = ''
    for ch in raw:
        if ch.isdigit() or (ch == '.' and '.' not in num) or (ch == '-' and not num):
            num += ch
        elif num:
            break
    try:
        v = float(num)
    except ValueError:
        return None
    feet = 'ft' in raw or "'" in raw or 'feet' in raw
    v = v if feet else v * 3.28084
    return int(round(v)) if -1000 < v < 30000 else None


def poi_name(tags, kind):
    name = tags.get('name')
    if not name and kind == FUEL:
        name = tags.get('brand') or tags.get('operator')
    return name.strip()[:60] if name else None
SIMPLIFY_PX = 0.02      # about 2.4 m at zoom 10 in Colorado
MIN_WATER_PX2 = 0.02    # drop ponds smaller than about 300 m^2


def tile_x(lon):
    return int((lon + 180) / 360 * 2 ** Z)


def tile_y(lat):
    r = math.radians(lat)
    return int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * 2 ** Z)


def to_px(coords):
    """lon/lat array -> zoom-10 global pixel coordinates (Web Mercator)."""
    lon, lat = coords[:, 0], np.clip(coords[:, 1], -85, 85)
    x = (lon + 180) / 360 * N
    r = np.radians(lat)
    y = (1 - np.log(np.tan(r) + 1 / np.cos(r)) / np.pi) / 2 * N
    return np.column_stack([x, y])


def label_for(tags, kind):
    if kind in (1, 2, 3):
        ref = tags.get('ref')
        if ref:
            return ref.split(';')[0].strip()
    name = tags.get('name')
    return name.strip() if name else None


def download(path):
    if os.path.exists(path):
        return
    print('downloading', PBF_URL)
    urllib.request.urlretrieve(PBF_URL, path + '.part')
    os.replace(path + '.part', path)


class Tiles:
    def __init__(self, x0, x1, y0, y1):
        self.x0, self.x1, self.y0, self.y1 = x0, x1, y0, y1
        self.features = defaultdict(list)   # (tx, ty) -> [(kind, name, [parts])]

    def add(self, geom_px, kind, name):
        if geom_px.is_empty:
            return
        minx, miny, maxx, maxy = geom_px.bounds
        for tx in range(max(self.x0, int(minx // 256)), min(self.x1, int(maxx // 256)) + 1):
            for ty in range(max(self.y0, int(miny // 256)), min(self.y1, int(maxy // 256)) + 1):
                part = shapely.clip_by_rect(geom_px, tx * 256, ty * 256, tx * 256 + 256, ty * 256 + 256)
                if part.is_empty:
                    continue
                parts = []
                for g in getattr(part, 'geoms', [part]):
                    if g.geom_type == 'LineString':
                        parts.append(np.asarray(g.coords))
                    elif g.geom_type == 'Polygon':
                        parts.append(np.asarray(g.exterior.coords))
                        parts.extend(np.asarray(r.coords) for r in g.interiors)
                if parts:
                    self.features[(tx, ty)].append((kind, name, [p - (tx * 256, ty * 256) for p in parts]))


def encode_tile(feats):
    feats.sort(key=lambda f: -f[0])  # water first, motorways last: drawing order
    names = sorted({f[1] for f in feats if f[1]})
    nidx = {n: i for i, n in enumerate(names)}
    out = bytearray(struct.pack('<H', len(names)))
    for n in names:
        b = n.encode('utf-8')[:255]
        out += struct.pack('<B', len(b)) + b
    body = bytearray()
    count = 0
    for kind, name, parts in feats:
        enc_parts = []
        for p in parts:
            q = np.clip(np.round(p * 256), 0, 65535).astype(np.int64)
            pts = [tuple(q[0])]
            for a, b in zip(q[:-1], q[1:]):
                # split long jumps so every step fits in an int16 delta
                steps = int(max(abs(b[0] - a[0]), abs(b[1] - a[1])) // 32000) + 1
                for s in range(1, steps + 1):
                    pts.append((a[0] + (b[0] - a[0]) * s // steps, a[1] + (b[1] - a[1]) * s // steps))
            if len(pts) < 2 or len(pts) > 65535:
                continue
            e = bytearray(struct.pack('<HHH', len(pts), pts[0][0], pts[0][1]))
            for (ax, ay), (bx, by) in zip(pts[:-1], pts[1:]):
                e += struct.pack('<hh', bx - ax, by - ay)
            enc_parts.append(e)
        if not enc_parts:
            continue
        body += struct.pack('<BHH', kind, nidx.get(name, 0xFFFF), len(enc_parts))
        for e in enc_parts:
            body += e
        count += 1
    return bytes(out + struct.pack('<I', count) + body)


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/assets/streets'
    pbf = sys.argv[2] if len(sys.argv) > 2 else '/tmp/' + _REGION.split('/')[-1] + '-latest.osm.pbf'
    download(pbf)
    os.makedirs(out_dir, exist_ok=True)
    x0, x1 = tile_x(BBOX['west']), tile_x(BBOX['east'])
    y0, y1 = tile_y(BBOX['north']), tile_y(BBOX['south'])
    tiles = Tiles(x0, x1, y0, y1)
    fab = osmium.geom.WKBFactory()
    stats = defaultdict(int)

    fp = (osmium.FileProcessor(pbf)
          .with_locations()
          .with_areas(osmium.filter.TagFilter(('natural', 'water'), ('waterway', 'riverbank'), ('landuse', 'reservoir'),
                                              ('amenity', 'fuel'), ('tourism', 'camp_site')))
          .with_filter(osmium.filter.KeyFilter('highway', 'waterway', 'natural', 'landuse', 'type',
                                               'mountain_pass', 'tourism', 'amenity')))
    pois = []

    def add_poi(kind, lat, lon, tags):
        if BBOX['south'] <= lat <= BBOX['north'] and BBOX['west'] <= lon <= BBOX['east']:
            pois.append([kind, round(lat, 5), round(lon, 5), poi_name(tags, kind), elevation_ft(tags) if kind in (PEAK, PASS) else None])

    for obj in fp:
        try:
            if obj.is_node():
                kind = poi_kind(obj.tags)
                if kind and obj.location.valid() and (kind not in (PEAK, PASS) or obj.tags.get('name')):
                    add_poi(kind, obj.location.lat, obj.location.lon, obj.tags)
                    stats['poi%d' % kind] += 1
            elif obj.is_way():
                tags = obj.tags
                kind = ROAD.get(tags.get('highway', ''))
                if kind is None and tags.get('waterway') == 'river':
                    kind = RIVER
                if kind is None or tags.get('area') == 'yes':
                    continue
                g = wkb.loads(fab.create_linestring(obj), hex=True)
                g = shapely.transform(g, to_px).simplify(SIMPLIFY_PX, preserve_topology=False)
                tiles.add(g, kind, label_for(tags, kind) if kind < 20 else None)
                stats[kind] += 1
            elif obj.is_area():
                tags = obj.tags
                kind = poi_kind(tags)
                if kind in (CAMP, FUEL):  # campgrounds and gas stations drawn as areas: use a point inside
                    pt = wkb.loads(fab.create_multipolygon(obj), hex=True).representative_point()
                    add_poi(kind, pt.y, pt.x, tags)
                    stats['poi%d' % kind] += 1
                    continue
                # Area assembly also builds neighborhoods, parks, farmland...: keep only water.
                if not (tags.get('natural') == 'water' or tags.get('waterway') == 'riverbank' or tags.get('landuse') == 'reservoir'):
                    continue
                g = wkb.loads(fab.create_multipolygon(obj), hex=True)
                g = shapely.transform(g, to_px)
                if g.area < MIN_WATER_PX2:
                    continue
                g = g.simplify(SIMPLIFY_PX, preserve_topology=True)
                if not g.is_valid:
                    g = shapely.make_valid(g)
                tiles.add(g, WATER, None)
                stats[WATER] += 1
        except Exception:
            stats['skipped'] += 1

    total = 0
    for (tx, ty), feats in tiles.features.items():
        data = encode_tile(feats)
        with open(os.path.join(out_dir, '%d_%d.bin' % (tx, ty)), 'wb') as f:
            f.write(data)
        total += len(data)
    # The same gas station or campground is often mapped as both a point and an area: keep one.
    pois.sort(key=lambda p: (p[3] is None, p[0]))
    kept, seen = [], set()
    for p in pois:
        cell = (p[0], round(p[1] * 1500), round(p[2] * 1200))  # about 75 m cells
        if p[0] in (CAMP, FUEL) and cell in seen:
            continue
        seen.add(cell)
        kept.append(p)
    with open(os.path.join(out_dir, 'pois.json'), 'w') as f:
        json.dump({'v': 1, 'p': kept}, f, separators=(',', ':'), ensure_ascii=False)
    stats['pois_kept'] = len(kept)
    with open(os.path.join(out_dir, 'index.json'), 'w') as f:
        json.dump({'z': Z, 'x0': x0, 'x1': x1, 'y0': y0, 'y1': y1, 'kinds': {
            'motorway': 1, 'trunk': 2, 'primary': 3, 'secondary': 4, 'tertiary': 5,
            'local': 6, 'track': 7, 'river': 20, 'water': 30}}, f)
    print('streets: %d tiles, %.1f MB, features %s' % (len(tiles.features), total / 1e6, dict(stats)))


if __name__ == '__main__':
    main()
