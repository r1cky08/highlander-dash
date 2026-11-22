#!/usr/bin/env python3
"""
Builds the offline place search index for Colorado from OpenStreetMap:
stores, restaurants, gas stations, parks, hospitals, hotels, towns and so on.

Source: Geofabrik's daily Colorado extract (the same file the street map uses)
  https://download.geofabrik.de/north-america/us/colorado.html
  Map data (c) OpenStreetMap contributors, available under the Open Database License.

Output: assets/places/places.json
  {"v": 1,
   "cats":   ["Gas station", "Grocery", ...],
   "cities": ["", "Denver", "Colorado Springs", ...],
   "p": [[name, cat_index, lat*1e5, lon*1e5, "1234 Main St", city_index], ...]}

Only named places are kept. Street addresses are not indexed; the launcher hands
anything it can't find to Google Maps instead.

Usage: python3 tools/fetch_places.py [output_dir] [input.osm.pbf]
"""
import os as _os
_REGION = _os.environ.get('MAP_REGION', 'north-america/us/colorado')   # Geofabrik path of your state or country
import json, os, sys, urllib.request

import osmium
from shapely import wkb

_bb = _os.environ.get('MAP_BBOX', '-109.3,36.8,-101.8,41.2').split(',')   # west,south,east,north of your area
BBOX = dict(west=float(_bb[0]), south=float(_bb[1]), east=float(_bb[2]), north=float(_bb[3]))
PBF_URL = 'https://download.geofabrik.de/' + _REGION + '-latest.osm.pbf'
KEYS = ('shop', 'amenity', 'tourism', 'leisure', 'office', 'healthcare', 'craft', 'place', 'aeroway', 'natural')

# Values worth searching for, per key. None = every value of that key.
KEEP = {
    'shop': None, 'craft': None, 'office': None, 'healthcare': None,
    'amenity': {'fuel', 'restaurant', 'fast_food', 'cafe', 'bar', 'pub', 'pharmacy', 'hospital', 'clinic', 'doctors',
                'dentist', 'veterinary', 'bank', 'post_office', 'library', 'school', 'college', 'university',
                'place_of_worship', 'police', 'fire_station', 'townhall', 'courthouse', 'cinema', 'theatre',
                'charging_station', 'car_wash', 'car_rental', 'ice_cream', 'community_centre', 'marketplace',
                'parking', 'bus_station', 'kindergarten', 'childcare', 'arts_centre', 'events_venue', 'nightclub',
                'food_court', 'biergarten', 'social_facility', 'recycling', 'animal_shelter', 'atm', 'casino'},
    'tourism': {'hotel', 'motel', 'hostel', 'guest_house', 'camp_site', 'caravan_site', 'museum', 'attraction',
                'viewpoint', 'zoo', 'theme_park', 'information', 'gallery', 'aquarium', 'picnic_site'},
    'leisure': {'park', 'golf_course', 'sports_centre', 'stadium', 'fitness_centre', 'swimming_pool', 'nature_reserve',
                'playground', 'pitch', 'ice_rink', 'water_park', 'marina', 'dog_park', 'bowling_alley', 'trampoline_park'},
    'place': {'city', 'town', 'village', 'hamlet', 'suburb', 'neighbourhood', 'locality'},
    'aeroway': {'aerodrome'},
    'natural': {'peak'},
}
LABEL = {
    'fuel': 'Gas station', 'fast_food': 'Fast food', 'supermarket': 'Grocery store', 'convenience': 'Convenience store',
    'doityourself': 'Hardware store', 'hardware': 'Hardware store', 'car_repair': 'Auto repair', 'car_parts': 'Auto parts',
    'place_of_worship': 'Church / place of worship', 'charging_station': 'EV charging', 'department_store': 'Department store',
    'mobile_phone': 'Phone store', 'hairdresser': 'Hair salon', 'beauty': 'Beauty salon', 'alcohol': 'Liquor store',
    'clothes': 'Clothing store', 'shoes': 'Shoe store', 'pet': 'Pet store', 'furniture': 'Furniture store',
    'electronics': 'Electronics store', 'sports': 'Sporting goods', 'outdoor': 'Outdoor gear', 'variety_store': 'Discount store',
    'gift': 'Gift shop', 'books': 'Bookstore', 'bakery': 'Bakery', 'butcher': 'Butcher', 'florist': 'Florist',
    'tyres': 'Tire shop', 'car': 'Car dealer', 'mall': 'Mall', 'optician': 'Optician', 'dry_cleaning': 'Dry cleaner',
    'laundry': 'Laundromat', 'cannabis': 'Dispensary', 'tobacco': 'Smoke shop', 'jewelry': 'Jewelry store',
    'doctors': 'Doctor', 'dentist': 'Dentist', 'veterinary': 'Vet', 'post_office': 'Post office', 'townhall': 'Town hall',
    'fire_station': 'Fire station', 'police': 'Police', 'car_wash': 'Car wash', 'car_rental': 'Car rental',
    'ice_cream': 'Ice cream', 'community_centre': 'Community center', 'bus_station': 'Bus station', 'atm': 'ATM',
    'guest_house': 'Guest house', 'camp_site': 'Campground', 'caravan_site': 'RV park', 'theme_park': 'Theme park',
    'picnic_site': 'Picnic area', 'golf_course': 'Golf course', 'sports_centre': 'Sports center',
    'fitness_centre': 'Gym', 'swimming_pool': 'Pool', 'nature_reserve': 'Nature reserve', 'ice_rink': 'Ice rink',
    'water_park': 'Water park', 'dog_park': 'Dog park', 'bowling_alley': 'Bowling', 'aerodrome': 'Airport',
    'peak': 'Mountain peak', 'city': 'City', 'town': 'Town', 'village': 'Village', 'hamlet': 'Community',
    'suburb': 'Neighborhood', 'neighbourhood': 'Neighborhood', 'locality': 'Place', 'arts_centre': 'Arts center',
    'events_venue': 'Event venue', 'food_court': 'Food court', 'social_facility': 'Social services',
    'animal_shelter': 'Animal shelter', 'trampoline_park': 'Trampoline park', 'estate_agent': 'Real estate office',
    'insurance': 'Insurance', 'government': 'Government office', 'lawyer': 'Lawyer', 'accountant': 'Accountant',
}


def label(key, value):
    if value in LABEL:
        return LABEL[value]
    if value in ('yes', ''):
        return {'shop': 'Store', 'office': 'Office', 'craft': 'Business', 'healthcare': 'Health care'}.get(key, key.title())
    return value.replace('_', ' ').replace(';', ', ').capitalize()


def classify(tags):
    for k in KEYS:
        v = tags.get(k)
        if v is None:
            continue
        allowed = KEEP.get(k)
        if allowed is None or v in allowed:
            return label(k, v)
    return None


def download(path):
    if os.path.exists(path):
        return
    print('downloading', PBF_URL)
    urllib.request.urlretrieve(PBF_URL, path + '.part')
    os.replace(path + '.part', path)


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/assets/places'
    pbf = sys.argv[2] if len(sys.argv) > 2 else '/tmp/' + _REGION.split('/')[-1] + '-latest.osm.pbf'
    download(pbf)
    os.makedirs(out_dir, exist_ok=True)
    fab = osmium.geom.WKBFactory()
    cats, cities, rows, seen = {}, {'': 0}, [], set()

    def idx(table, value):
        if value not in table:
            table[value] = len(table)
        return table[value]

    def add(tags, lat, lon):
        if not (BBOX['south'] <= lat <= BBOX['north'] and BBOX['west'] <= lon <= BBOX['east']):
            return
        name = tags.get('name') or tags.get('brand')
        cat = classify(tags)
        if not name or not cat:
            return
        name = name.strip()[:80]
        key = (name.lower(), round(lat, 3), round(lon, 3))   # same place mapped as a point and a building
        if key in seen:
            return
        seen.add(key)
        addr = ''
        if tags.get('addr:housenumber') and tags.get('addr:street'):
            addr = tags.get('addr:housenumber') + ' ' + tags.get('addr:street')
        city = (tags.get('addr:city') or '').strip()
        rows.append([name, idx(cats, cat), round(lat * 1e5), round(lon * 1e5), addr[:60], idx(cities, city)])

    fp = (osmium.FileProcessor(pbf)
          .with_locations()
          .with_areas(osmium.filter.KeyFilter(*KEYS))
          .with_filter(osmium.filter.KeyFilter(*KEYS)))
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

    rows.sort(key=lambda r: r[0].lower())
    data = {'v': 1, 'cats': [c for c, _ in sorted(cats.items(), key=lambda kv: kv[1])],
            'cities': [c for c, _ in sorted(cities.items(), key=lambda kv: kv[1])], 'p': rows}
    with open(os.path.join(out_dir, 'places.json'), 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, separators=(',', ':'))
    print('places', len(rows), 'categories', len(cats), 'cities', len(cities))


if __name__ == '__main__':
    main()
