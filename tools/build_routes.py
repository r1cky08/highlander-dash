#!/usr/bin/env python3
"""
Builds the offline routing graph for Colorado from OpenStreetMap (Geofabrik's extract).
Map data (c) OpenStreetMap contributors, ODbL.

Output: <out_dir>/graph.rg, little-endian, read by Router.java through a memory map.
  Header: 40 x int32  (see HDR below), then the sections in the order of SECTIONS.
Nodes are road junctions and road ends. A "segment" is the stretch of road between two nodes,
with its shape; each segment becomes one or two directed edges.

Usage: python3 tools/build_routes.py out_dir input.osm.pbf
"""
import os as _os
_REGION = _os.environ.get('MAP_REGION', 'north-america/us/colorado')   # Geofabrik path of your state or country
import array, math, os, re, struct, sys
import numpy as np
import osmium

# class code: (routing kph cap, factor on posted speed)
CLASS = {
    'motorway': (0, 105), 'motorway_link': (1, 55), 'trunk': (2, 90), 'trunk_link': (3, 50),
    'primary': (4, 80), 'primary_link': (5, 45), 'secondary': (6, 70), 'secondary_link': (7, 40),
    'tertiary': (8, 55), 'tertiary_link': (9, 35), 'unclassified': (10, 55), 'residential': (11, 40),
    'living_street': (12, 15), 'road': (10, 40),
}
SCALE = 1_000_000          # degrees to int
CELL = 10_000              # grid cell, 0.01 degree
KY = 111_320.0
KX = KY * math.cos(math.radians(39.0))


def parse_speed(v, default):
    if not v:
        return default
    m = re.match(r'\s*(\d+)\s*(mph|km/h|kph)?', v)
    if not m:
        return default
    n = int(m.group(1))
    kph = n if m.group(2) in ('km/h', 'kph') else n * 1.609   # US data: a bare number is mph
    return int(kph) if 8 <= kph <= 130 else default


class Roads(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.refs = array.array('q')
        self.lat = array.array('i')
        self.lon = array.array('i')
        self.wstart = array.array('q', [0])
        self.wmeta = []      # (class, speed, oneway, roundabout, name_idx)
        self.names = {'': 0}
        self.namelist = ['']

    def name_idx(self, s):
        i = self.names.get(s)
        if i is None:
            i = self.names[s] = len(self.namelist)
            self.namelist.append(s)
        return i

    def way(self, w):
        t = w.tags
        hw = t.get('highway')
        c = CLASS.get(hw)
        if c is None or t.get('area') == 'yes':
            return
        if t.get('motor_vehicle') in ('no', 'private') or t.get('vehicle') in ('no', 'private'):
            return
        if t.get('access') in ('no', 'private') and t.get('motor_vehicle') not in ('yes', 'designated', 'permissive'):
            return
        if t.get('service') or t.get('construction') or t.get('highway') == 'construction':
            return
        try:
            pts = [(n.ref, n.location.lat, n.location.lon) for n in w.nodes]
        except osmium.InvalidLocationError:
            return
        if len(pts) < 2:
            return
        cls, cap = c
        speed = parse_speed(t.get('maxspeed'), cap)
        speed = max(8, int(speed * (0.95 if cls <= 3 else 0.8)))
        junction = t.get('junction')
        ow = t.get('oneway')
        o = 0
        if ow in ('yes', 'true', '1'):
            o = 1
        elif ow == '-1':
            o = 2
        elif ow in ('no', 'false', '0'):
            o = 0
        elif junction in ('roundabout', 'circular') or hw in ('motorway', 'motorway_link'):
            o = 1
        rb = 1 if junction in ('roundabout', 'circular') else 0
        name, ref = t.get('name', ''), t.get('ref', '')
        ref = ref.split(';')[0].strip()
        label = (ref or name) if cls <= 3 and ref else (name or ref)
        self.wmeta.append((cls, speed, o, rb, self.name_idx(label)))
        for r, la, lo in pts:
            self.refs.append(r)
            self.lat.append(int(round(la * SCALE)))
            self.lon.append(int(round(lo * SCALE)))
        self.wstart.append(len(self.refs))


def main():
    out_dir, pbf = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    if not os.path.exists(pbf):
        import urllib.request
        url = 'https://download.geofabrik.de/' + _REGION + '-latest.osm.pbf'
        print('downloading', url)
        urllib.request.urlretrieve(url, pbf)
    h = Roads()
    h.apply_file(pbf, locations=True, idx='flex_mem')
    print('ways', len(h.wmeta), 'node refs', len(h.refs))

    refs = np.frombuffer(h.refs, dtype=np.int64)
    lat = np.frombuffer(h.lat, dtype=np.int32)
    lon = np.frombuffer(h.lon, dtype=np.int32)
    ws = np.frombuffer(h.wstart, dtype=np.int64)
    nW = len(h.wmeta)
    wid = np.repeat(np.arange(nW), np.diff(ws))                  # way of every ref position

    # Junctions: nodes used by two or more ways (or twice in one way), and the ends of every way.
    uniq, first, cnt = np.unique(refs, return_index=True, return_counts=True)
    is_node_u = cnt >= 2
    ends = np.concatenate([ws[:-1], ws[1:] - 1])
    end_ids = refs[ends]
    node_ids = np.union1d(uniq[is_node_u], end_ids)
    pos_in = np.searchsorted(uniq, node_ids)
    nlat = lat[first[pos_in]]
    nlon = lon[first[pos_in]]
    nN = len(node_ids)
    is_inter = np.isin(refs, node_ids)
    cuts = np.flatnonzero(is_inter)
    same = wid[cuts[:-1]] == wid[cuts[1:]]
    si, sj = cuts[:-1][same], cuts[1:][same]                     # segment = ref positions i..j
    nS = len(si)
    print('nodes', nN, 'segments', nS)

    # Segment length from the full-detail points.
    dy = np.diff(lat.astype(np.float64)) / SCALE * KY
    dx = np.diff(lon.astype(np.float64)) / SCALE * KX
    step = np.hypot(dx, dy)
    step = np.concatenate([[0.0], step])
    step[ws[:-1]] = 0.0                                          # no step across way boundaries
    cs = np.cumsum(step)
    slen = np.maximum(1, np.rint(cs[sj] - cs[si])).astype(np.int64)

    A = np.searchsorted(node_ids, refs[si]).astype(np.int32)
    B = np.searchsorted(node_ids, refs[sj]).astype(np.int32)

    # Shapes, simplified to about 3 m.
    X = (lon.astype(np.float64) / SCALE * KX).tolist()
    Y = (lat.astype(np.float64) / SCALE * KY).tolist()
    latl, lonl = lat.tolist(), lon.tolist()
    seg_start = array.array('i', [0])
    pl = array.array('i'); po = array.array('i')
    si_l, sj_l = si.tolist(), sj.tolist()
    for i, j in zip(si_l, sj_l):
        if j - i == 1:
            keep = (i, j)
        else:
            flag = {i, j}
            stack = [(i, j)]
            while stack:
                a, b = stack.pop()
                if b - a < 2:
                    continue
                ax, ay = X[a], Y[a]
                vx, vy = X[b] - ax, Y[b] - ay
                L2 = vx * vx + vy * vy
                best, bk = 0.0, -1
                for k in range(a + 1, b):
                    px, py = X[k] - ax, Y[k] - ay
                    if L2 == 0:
                        d = math.hypot(px, py)
                    else:
                        t = max(0.0, min(1.0, (px * vx + py * vy) / L2))
                        d = math.hypot(px - t * vx, py - t * vy)
                    if d > best:
                        best, bk = d, k
                if best > 3.0:
                    flag.add(bk)
                    stack.append((a, bk)); stack.append((bk, b))
            keep = sorted(flag)
        for k in keep:
            pl.append(latl[k]); po.append(lonl[k])
        seg_start.append(len(pl))
    print('shape points', len(pl))

    # Directed edges.
    meta = np.array(h.wmeta, dtype=np.int32)
    sw = wid[si]
    cls, spd, ow, rb, nm = (meta[sw, c] for c in range(5))
    e_from, e_to, e_seg, e_len, e_name, e_meta = [], [], [], [], [], []
    packed = (spd | (cls << 8) | (rb << 16) | ((ow > 0).astype(np.int32) << 17)).astype(np.int32)
    ids = np.arange(nS, dtype=np.int32)
    fwd = ow != 2
    rev = ow != 1
    for mask, f, t, r in ((fwd, A, B, 0), (rev, B, A, 1)):
        e_from.append(f[mask]); e_to.append(t[mask]); e_seg.append(ids[mask] * 2 + r)
        e_len.append(slen[mask]); e_name.append(nm[mask]); e_meta.append(packed[mask])
    e_from = np.concatenate(e_from); e_to = np.concatenate(e_to); e_seg = np.concatenate(e_seg)
    e_len = np.concatenate(e_len).astype(np.int32); e_name = np.concatenate(e_name).astype(np.int32); e_meta = np.concatenate(e_meta)
    order = np.argsort(e_from, kind='stable')
    e_from, e_to, e_seg, e_len, e_name, e_meta = (a[order] for a in (e_from, e_to, e_seg, e_len, e_name, e_meta))
    first_out = np.searchsorted(e_from, np.arange(nN + 1)).astype(np.int32)
    nE = len(e_to)
    print('edges', nE)

    # Grid for snapping a point to the nearest segment.
    lat0, lon0 = int(nlat.min()) - CELL, int(nlon.min()) - CELL
    gw = (int(nlon.max()) - lon0) // CELL + 2
    gh = (int(nlat.max()) - lat0) // CELL + 2
    cells, segs = array.array('i'), array.array('i')
    pa, po_ = pl, po
    for s in range(nS):
        a, b = seg_start[s], seg_start[s + 1]
        seen = set()
        for k in range(a, b):
            la, lo = pa[k], po_[k]
            seen.add(((la - lat0) // CELL) * gw + (lo - lon0) // CELL)
            if k + 1 < b:
                nla, nlo = pa[k + 1], po_[k + 1]
                dm = math.hypot((nla - la) / SCALE * KY, (nlo - lo) / SCALE * KX)
                n = int(dm // 500)
                for q in range(1, n + 1):
                    f = q / (n + 1)
                    seen.add(((int(la + (nla - la) * f) - lat0) // CELL) * gw + (int(lo + (nlo - lo) * f) - lon0) // CELL)
        for c in seen:
            cells.append(c); segs.append(s)
    cells = np.frombuffer(cells, dtype=np.int32); segs = np.frombuffer(segs, dtype=np.int32)
    o = np.argsort(cells, kind='stable')
    cells, segs = cells[o], segs[o]
    grid_start = np.searchsorted(cells, np.arange(gw * gh + 1)).astype(np.int32)

    # Names.
    nb = [s.encode('utf-8') for s in h.namelist]
    name_off = np.concatenate([[0], np.cumsum([len(b) for b in nb])]).astype(np.int32)
    name_bytes = b''.join(nb)

    sections = [
        nlat.astype(np.int32), nlon.astype(np.int32), first_out,
        e_to.astype(np.int32), e_len, e_seg.astype(np.int32), e_name, e_meta,
        np.frombuffer(seg_start, dtype=np.int32), np.frombuffer(pl, dtype=np.int32), np.frombuffer(po, dtype=np.int32),
        name_off, np.frombuffer(name_bytes + b'\0' * (-len(name_bytes) % 4), dtype=np.int32),
        grid_start, segs.astype(np.int32), A.astype(np.int32), B.astype(np.int32),
    ]
    HDR = 40
    head = [0] * HDR
    head[0] = 0x31474C48  # 'HLG1'
    head[1:11] = [1, nN, nE, nS, len(pl), len(nb), len(name_bytes), gw, gh, lat0]
    head[11] = lon0; head[12] = CELL; head[13] = len(segs)
    off = HDR * 4
    offs = []
    for s in sections:
        offs.append(off); off += s.nbytes
    head[14:14 + len(offs)] = offs
    path = os.path.join(out_dir, 'graph.rg')
    with open(path, 'wb') as f:
        f.write(struct.pack('<%di' % HDR, *head))
        for s in sections:
            f.write(np.ascontiguousarray(s).astype('<i4', copy=False).tobytes())
    print('wrote', path, os.path.getsize(path) // 1024 // 1024, 'MB')


if __name__ == '__main__':
    main()
