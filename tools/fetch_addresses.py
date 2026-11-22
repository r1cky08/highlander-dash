#!/usr/bin/env python3
"""
Builds the offline street address index for Colorado from OpenStreetMap
(addr:housenumber + addr:street on points and buildings).

Source: Geofabrik's daily Colorado extract. Map data (c) OpenStreetMap contributors (ODbL).

Output: assets/addresses/<xx>.json, one file per first two letters of the street's core name
(directional and suffix words dropped), so the launcher only loads what a search needs.
  {"c": [city, ...], "s": [[street, city_index, [num, lat1e5, lon1e5, ...]], ...]}
Each street keeps at most MAXPTS evenly spaced numbers; the launcher interpolates between them.

norm() here and normStreet() in index.html must stay identical.
Usage: python3 tools/fetch_addresses.py [output_dir] [input.osm.pbf]
"""
import os as _os
_REGION = _os.environ.get('MAP_REGION', 'north-america/us/colorado')   # Geofabrik path of your state or country
import json, os, re, sys, urllib.request
import osmium
from shapely import wkb

_bb = _os.environ.get('MAP_BBOX', '-109.3,36.8,-101.8,41.2').split(',')   # west,south,east,north of your area
BBOX = dict(west=float(_bb[0]), south=float(_bb[1]), east=float(_bb[2]), north=float(_bb[3]))
PBF_URL = 'https://download.geofabrik.de/' + _REGION + '-latest.osm.pbf'
MAXPTS = 10
ABBR = {'st': 'street', 'ave': 'avenue', 'av': 'avenue', 'blvd': 'boulevard', 'dr': 'drive', 'rd': 'road', 'ln': 'lane', 'ct': 'court',
        'cir': 'circle', 'pl': 'place', 'pkwy': 'parkway', 'hwy': 'highway', 'trl': 'trail', 'ter': 'terrace', 'way': 'way',
        'n': 'north', 's': 'south', 'e': 'east', 'w': 'west'}
DIRS = {'north', 'south', 'east', 'west'}
SUFFIX = {'street', 'avenue', 'boulevard', 'drive', 'road', 'lane', 'court', 'circle', 'place', 'parkway', 'highway', 'trail', 'terrace', 'way'}


def norm(s):
    w = re.sub(r'[^a-z0-9 ]', ' ', s.lower()).split()
    return ' '.join(ABBR.get(x, x) for x in w)


def core(s):
    w = norm(s).split()
    while len(w) > 1 and w[0] in DIRS:
        w = w[1:]
    while len(w) > 1 and w[-1] in SUFFIX | DIRS:
        w = w[:-1]
    return ' '.join(w)


def shard(c):
    k = re.sub(r'[^a-z0-9]', '', c)[:2]
    return (k + '_')[:2]


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/assets/addresses'
    pbf = sys.argv[2] if len(sys.argv) > 2 else '/tmp/' + _REGION.split('/')[-1] + '-latest.osm.pbf'
    if not os.path.exists(pbf):
        urllib.request.urlretrieve(PBF_URL, pbf + '.part'); os.replace(pbf + '.part', pbf)
    os.makedirs(out, exist_ok=True)
    fab = osmium.geom.WKBFactory()
    streets = {}   # (street display, city) -> {num: (lat, lon)}

    def add(tags, lat, lon):
        if not (BBOX['south'] <= lat <= BBOX['north'] and BBOX['west'] <= lon <= BBOX['east']):
            return
        st = (tags.get('addr:street') or '').strip()
        hn = (tags.get('addr:housenumber') or '').strip()
        m = re.match(r'^(\d+)', hn)
        if not st or not m:
            return
        key = (st[:50], (tags.get('addr:city') or '').strip()[:40])
        streets.setdefault(key, {})[int(m.group(1))] = (round(lat * 1e5), round(lon * 1e5))

    fp = (osmium.FileProcessor(pbf).with_locations().with_areas(osmium.filter.KeyFilter('addr:housenumber'))
          .with_filter(osmium.filter.KeyFilter('addr:housenumber')))
    for obj in fp:
        tags = dict(obj.tags)
        if obj.is_node():
            if obj.location.valid():
                add(tags, obj.location.lat, obj.location.lon)
        elif obj.is_area():
            try:
                pt = wkb.loads(fab.create_multipolygon(obj), hex=True).representative_point()
            except Exception:
                continue
            add(tags, pt.y, pt.x)

    shards = {}
    for (st, city), nums in streets.items():
        ks = sorted(nums)
        if len(ks) > MAXPTS:
            ks = [ks[round(i * (len(ks) - 1) / (MAXPTS - 1))] for i in range(MAXPTS)]
        flat = []
        for n in ks:
            flat += [n, nums[n][0], nums[n][1]]
        shards.setdefault(shard(core(st)), []).append((st, city, flat))
    total = 0
    for k, rows in shards.items():
        cities = ['']
        outrows = []
        for st, city, flat in sorted(rows):
            if city not in cities:
                cities.append(city)
            outrows.append([st, cities.index(city), flat])
            total += len(flat) // 3
        with open(os.path.join(out, k + '.json'), 'w', encoding='utf-8') as f:
            json.dump({'c': cities, 's': outrows}, f, ensure_ascii=False, separators=(',', ':'))
    print('streets', len(streets), 'points', total, 'shards', len(shards))


if __name__ == '__main__':
    main()
