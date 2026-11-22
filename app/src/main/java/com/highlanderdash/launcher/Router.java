package com.highlanderdash.launcher;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Offline driving routes over the Colorado graph built by tools/build_routes.py.
 * Plain Java (no Android classes) so it can be tested on a computer. The graph is read straight
 * from a memory-mapped file, so it adds almost nothing to the app's heap.
 */
final class Router {
    private static final double SCALE = 1e6, KY = 111320.0, KX = KY * Math.cos(Math.toRadians(39.0));
    private static final double MAX_MS = 125 / 3.6;          // fastest road we ever route at (for A*)

    private final ByteBuffer b;
    private final int nN, nE, nS, gw, gh, lat0, lon0, cell;
    private final int oLat, oLon, oFirst, oTo, oLen, oSeg, oName, oMeta, oSegStart, oPtLat, oPtLon, oNameOff, oNameBytes, oGridStart, oGridSegs, oSegA, oSegB;

    // Search scratch space, reused between routes.
    private int gen = 0;
    private final int[] stamp;
    private final float[] g;
    private final int[] prev;
    private float[] hf = new float[1 << 16];
    private int[] hn = new int[1 << 16];
    private int hs = 0;

    Router(ByteBuffer buf) {
        b = buf.order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x31474C48) throw new IllegalArgumentException("not a route graph");
        nN = b.getInt(8); nE = b.getInt(12); nS = b.getInt(16);
        gw = b.getInt(32); gh = b.getInt(36); lat0 = b.getInt(40); lon0 = b.getInt(44); cell = b.getInt(48);
        int[] o = new int[17];
        for (int i = 0; i < 17; i++) o[i] = b.getInt((14 + i) * 4);
        oLat = o[0]; oLon = o[1]; oFirst = o[2]; oTo = o[3]; oLen = o[4]; oSeg = o[5]; oName = o[6]; oMeta = o[7];
        oSegStart = o[8]; oPtLat = o[9]; oPtLon = o[10]; oNameOff = o[11]; oNameBytes = o[12]; oGridStart = o[13]; oGridSegs = o[14]; oSegA = o[15]; oSegB = o[16];
        stamp = new int[nN]; g = new float[nN]; prev = new int[nN];
    }

    private int I(int off, int i) { return b.getInt(off + i * 4); }
    private double nlat(int n) { return I(oLat, n) / SCALE; }
    private double nlon(int n) { return I(oLon, n) / SCALE; }
    private static double dist(double la1, double lo1, double la2, double lo2) {
        double dy = (la2 - la1) * KY, dx = (lo2 - lo1) * KX; return Math.sqrt(dx * dx + dy * dy);
    }
    String name(int i) {
        int a = I(oNameOff, i), e = I(oNameOff, i + 1);
        byte[] x = new byte[e - a]; for (int k = 0; k < x.length; k++) x[k] = b.get(oNameBytes + a + k);
        return new String(x, StandardCharsets.UTF_8);
    }

    // ----- snapping a point to the nearest road segment -----
    private static final class Snap {
        int seg, k; double t, lat, lon, along, d;   // k = shape point before the snapped point, along = meters from the segment's start
    }
    private int segPts(int seg) { return I(oSegStart, seg + 1) - I(oSegStart, seg); }
    private double ptLat(int seg, int k) { return I(oPtLat, I(oSegStart, seg) + k) / SCALE; }
    private double ptLon(int seg, int k) { return I(oPtLon, I(oSegStart, seg) + k) / SCALE; }

    private Snap snap(double lat, double lon) {
        int cy = (int) Math.floor((lat * SCALE - lat0) / cell), cx = (int) Math.floor((lon * SCALE - lon0) / cell);
        Snap best = null;
        for (int r = 0; r <= 6; r++) {
            for (int y = cy - r; y <= cy + r; y++) for (int x = cx - r; x <= cx + r; x++) {
                if (Math.max(Math.abs(y - cy), Math.abs(x - cx)) != r) continue;
                if (y < 0 || x < 0 || y >= gh || x >= gw) continue;
                int c = y * gw + x, a = I(oGridStart, c), e = I(oGridStart, c + 1);
                for (int q = a; q < e; q++) {
                    int seg = I(oGridSegs, q), n = segPts(seg);
                    for (int k = 0; k + 1 < n; k++) {
                        double ax = (ptLon(seg, k) - lon) * KX, ay = (ptLat(seg, k) - lat) * KY;
                        double bx = (ptLon(seg, k + 1) - lon) * KX, by = (ptLat(seg, k + 1) - lat) * KY;
                        double vx = bx - ax, vy = by - ay, L2 = vx * vx + vy * vy;
                        double t = L2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * vx + ay * vy) / L2));
                        double ex = ax + vx * t, ey = ay + vy * t, d = Math.sqrt(ex * ex + ey * ey);
                        if (best == null || d < best.d) {
                            if (best == null) best = new Snap();
                            best.seg = seg; best.k = k; best.t = t; best.d = d;
                            best.lat = lat + ey / KY; best.lon = lon + ex / KX;
                        }
                    }
                }
            }
            if (best != null && r >= 1 && best.d < r * cell / SCALE * KY) break;   // nothing nearer can be in the next ring
        }
        if (best == null || best.d > 3000) return null;
        // Distance along the segment from its first point, scaled to the true length.
        double along = 0, full = 0; int n = segPts(best.seg);
        for (int k = 0; k + 1 < n; k++) {
            double s = dist(ptLat(best.seg, k), ptLon(best.seg, k), ptLat(best.seg, k + 1), ptLon(best.seg, k + 1));
            if (k < best.k) along += s; else if (k == best.k) along += s * best.t;
            full += s;
        }
        int e = edgeOf(best.seg, 0), e2 = e < 0 ? edgeOf(best.seg, 1) : e;
        double real = e2 >= 0 ? I(oLen, e2) : full;
        best.along = full > 0 ? along * real / full : 0;
        return best;
    }

    // The directed edge for a segment: dir 0 = from its first node to its second, 1 = the other way. -1 if driving is not allowed that way.
    private int edgeOf(int seg, int dir) {
        int from = dir == 0 ? I(oSegA, seg) : I(oSegB, seg), code = seg * 2 + dir;
        for (int e = I(oFirst, from), end = I(oFirst, from + 1); e < end; e++) if (I(oSeg, e) == code) return e;
        return -1;
    }
    private int segLen(int seg) { int e = edgeOf(seg, 0); if (e < 0) e = edgeOf(seg, 1); return e < 0 ? 0 : I(oLen, e); }
    private static double secs(int len, int meta, boolean avoid) {
        int cls = (meta >> 8) & 0xFF, spd = meta & 0xFF;
        double s = len / (spd / 3.6);
        if (avoid && cls <= 3) s *= 6;
        return s;
    }
    private double edgeCost(int e, boolean avoid) {
        int meta = I(oMeta, e), cls = (meta >> 8) & 0xFF;
        return secs(I(oLen, e), meta, avoid) + (cls > 3 ? 2.5 : 0.5);   // a little for each junction
    }

    // ----- heap -----
    private void push(float f, int n) {
        if (hs == hf.length) { hf = Arrays.copyOf(hf, hs * 2); hn = Arrays.copyOf(hn, hs * 2); }
        int i = hs++;
        while (i > 0) { int p = (i - 1) >> 1; if (hf[p] <= f) break; hf[i] = hf[p]; hn[i] = hn[p]; i = p; }
        hf[i] = f; hn[i] = n;
    }
    private void pop() {
        hs--; if (hs == 0) return;
        float f = hf[hs]; int n = hn[hs], i = 0;
        while (true) {
            int c = 2 * i + 1; if (c >= hs) break;
            if (c + 1 < hs && hf[c + 1] < hf[c]) c++;
            if (hf[c] >= f) break;
            hf[i] = hf[c]; hn[i] = hn[c]; i = c;
        }
        hf[i] = f; hn[i] = n;
    }

    // ----- the route -----
    /** Returns JSON: {ok, dist (m), time (s), pts [lat,lon,...], steps [{t, at, d}]} or {ok:false, err}. */
    String route(double lat, double lon, double dlat, double dlon, double heading, boolean avoid) {
        Snap s = snap(lat, lon), d = snap(dlat, dlon);
        if (s == null) return "{\"ok\":false,\"err\":\"You are not near a mapped road.\"}";
        if (d == null) return "{\"ok\":false,\"err\":\"That place is not near a mapped road.\"}";
        int sA = I(oSegA, s.seg), sB = I(oSegB, s.seg), dC = I(oSegA, d.seg), dE = I(oSegB, d.seg);
        int sf = edgeOf(s.seg, 0), sr = edgeOf(s.seg, 1), df = edgeOf(d.seg, 0), dr = edgeOf(d.seg, 1);
        int sLen = segLen(s.seg), dLen = segLen(d.seg);
        gen++; hs = 0;
        double dLat = dlat, dLon = dlon;
        double best = Double.MAX_VALUE; int bestNode = -1, bestVia = -2;   // via 0 = enter from C, 1 = enter from E, -1 = same segment

        // Starting legs from the snapped point to either end of its segment.
        double travelBearing;
        if (sf >= 0) {                       // toward B
            double c = secs((int) Math.max(0, sLen - s.along), I(oMeta, sf), avoid);
            c += turnPenalty(s, true, heading);
            relax(sB, (float) c, -1, dLat, dLon);
        }
        if (sr >= 0) {                       // toward A
            double c = secs((int) Math.max(0, s.along), I(oMeta, sr), avoid);
            c += turnPenalty(s, false, heading);
            relax(sA, (float) c, -2, dLat, dLon);
        }
        if (s.seg == d.seg) {
            if (sf >= 0 && d.along >= s.along) { best = secs((int) (d.along - s.along), I(oMeta, sf), avoid); bestVia = -1; }
            else if (sr >= 0 && d.along <= s.along) { best = secs((int) (s.along - d.along), I(oMeta, sr), avoid); bestVia = -1; }
        }
        while (hs > 0) {
            float f = hf[0]; int u = hn[0]; pop();
            if (f > best) break;
            double hu = dist(nlat(u), nlon(u), dLat, dLon) / MAX_MS;
            if (f > g[u] + hu + 0.01) continue;     // stale entry
            if (u == dC && df >= 0) {
                double c = g[u] + secs((int) d.along, I(oMeta, df), avoid);
                if (c < best) { best = c; bestNode = u; bestVia = 0; }
            }
            if (u == dE && dr >= 0) {
                double c = g[u] + secs((int) Math.max(0, dLen - d.along), I(oMeta, dr), avoid);
                if (c < best) { best = c; bestNode = u; bestVia = 1; }
            }
            for (int e = I(oFirst, u), end = I(oFirst, u + 1); e < end; e++) {
                relax(I(oTo, e), (float) (g[u] + edgeCost(e, avoid)), e, dLat, dLon);
            }
        }
        if (bestVia == -2) return "{\"ok\":false,\"err\":\"No route found.\"}";
        return build(s, d, bestNode, bestVia, best, avoid);
    }

    private void relax(int v, float cost, int via, double dLat, double dLon) {
        if (stamp[v] == gen && g[v] <= cost) return;
        stamp[v] = gen; g[v] = cost; prev[v] = via;
        push((float) (cost + dist(nlat(v), nlon(v), dLat, dLon) / MAX_MS), v);
    }

    // Leaving a point against the way the car is facing means a U-turn: make it expensive.
    private double turnPenalty(Snap s, boolean towardB, double heading) {
        if (heading < 0) return 0;
        int n = segPts(s.seg), k = s.k;
        double la1 = ptLat(s.seg, k), lo1 = ptLon(s.seg, k), la2 = ptLat(s.seg, k + 1), lo2 = ptLon(s.seg, k + 1);
        double br = Math.toDegrees(Math.atan2((lo2 - lo1) * KX, (la2 - la1) * KY));
        if (!towardB) br += 180;
        double diff = Math.abs(((br - heading) % 360 + 540) % 360 - 180);
        return diff > 100 ? 30 : 0;
    }

    // ----- building the answer: polyline and spoken steps -----
    private static final class Piece { int name, cls, rb, startPt, node; double len; }

    private String build(Snap s, Snap d, int endNode, int via, double secs, boolean avoid) {
        ArrayList<Integer> edges = new ArrayList<>();
        int startVia = 0;
        if (via >= 0) {
            int n = endNode;
            while (true) {
                int p = prev[n];
                if (p < 0) { startVia = p; break; }
                edges.add(p);
                n = fromNodeOfEdge(p);
            }
            java.util.Collections.reverse(edges);
        }
        ArrayList<double[]> pts = new ArrayList<>();
        ArrayList<Piece> pieces = new ArrayList<>();
        int sA = I(oSegA, s.seg), sB = I(oSegB, s.seg);
        pts.add(new double[]{s.lat, s.lon});
        if (via == -1) {
            boolean fwd = d.along >= s.along;
            appendRange(pts, s.seg, fwd ? s.k + 1 : s.k, fwd ? d.k : d.k + 1, fwd);
            pts.add(new double[]{d.lat, d.lon});
            Piece p = new Piece(); p.name = nameOfSeg(s.seg); p.cls = clsOfSeg(s.seg); p.startPt = 0; p.node = -1; p.len = Math.abs(d.along - s.along); pieces.add(p);
        } else {
            // start leg
            Piece p0 = new Piece(); p0.name = nameOfSeg(s.seg); p0.cls = clsOfSeg(s.seg); p0.rb = rbOfSeg(s.seg); p0.startPt = 0; p0.node = -1;
            if (startVia == -1) { appendRange(pts, s.seg, s.k + 1, segPts(s.seg) - 1, true); p0.len = Math.max(1, segLen(s.seg) - s.along); }
            else { appendRange(pts, s.seg, s.k, 0, false); p0.len = Math.max(1, s.along); }
            pieces.add(p0);
            int node = startVia == -1 ? sB : sA;
            for (int e : edges) {
                int code = I(oSeg, e), seg = code >> 1, rev = code & 1;
                Piece p = new Piece(); p.name = I(oName, e); p.cls = (I(oMeta, e) >> 8) & 0xFF; p.rb = (I(oMeta, e) >> 16) & 1; p.node = node; p.len = I(oLen, e);
                p.startPt = pts.size() - 1;
                int n = segPts(seg);
                if (rev == 0) appendRange(pts, seg, 1, n - 1, true); else appendRange(pts, seg, n - 2, 0, false);
                pieces.add(p);
                node = I(oTo, e);
            }
            // destination leg
            Piece pd = new Piece(); pd.name = nameOfSeg(d.seg); pd.cls = clsOfSeg(d.seg); pd.rb = rbOfSeg(d.seg); pd.node = node; pd.startPt = pts.size() - 1;
            int dn = segPts(d.seg);
            if (via == 0) { appendRange(pts, d.seg, 1, d.k, true); pd.len = Math.max(1, d.along); }
            else { appendRange(pts, d.seg, dn - 2, d.k + 1, false); pd.len = Math.max(1, segLen(d.seg) - d.along); }
            pts.add(new double[]{d.lat, d.lon});
            pieces.add(pd);
        }
        // Distance along the polyline for every point.
        int N = pts.size(); double[] cum = new double[N];
        for (int i = 1; i < N; i++) cum[i] = cum[i - 1] + dist(pts.get(i - 1)[0], pts.get(i - 1)[1], pts.get(i)[0], pts.get(i)[1]);
        double total = cum[N - 1];

        // Group pieces into runs (same road, no sharp turn at a junction) and describe each change.
        StringBuilder st = new StringBuilder();
        int runStart = 0; boolean firstStep = true; int stepsOut = 0;
        ArrayList<int[]> runs = new ArrayList<>();   // {first piece, last piece}
        int rs = 0;
        for (int i = 1; i < pieces.size(); i++) {
            Piece a = pieces.get(i - 1), c = pieces.get(i);
            boolean split = a.name != c.name || a.rb != c.rb;
            if (!split && c.node >= 0) {
                int deg = I(oFirst, c.node + 1) - I(oFirst, c.node);
                if (deg >= 3) { double ang = turnAngle(pts, cum, c.startPt); if (Math.abs(ang) >= 45) split = true; }
            }
            if (split) { runs.add(new int[]{rs, i - 1}); rs = i; }
        }
        runs.add(new int[]{rs, pieces.size() - 1});

        for (int r = 0; r < runs.size(); r++) {
            Piece first = pieces.get(runs.get(r)[0]);
            int at = first.startPt;
            double endM = r + 1 < runs.size() ? cum[pieces.get(runs.get(r + 1)[0]).startPt] : total;
            String text;
            if (r == 0) {
                text = "Head " + compass(bearing(pts, cum, at, true)) + (nm(first) == null ? "" : " on " + nm(first));
            } else {
                Piece before = pieces.get(runs.get(r - 1)[1]);
                double ang = turnAngle(pts, cum, at);
                text = maneuver(before, first, ang, runs, r, pieces);
            }
            if (stepsOut++ > 0) st.append(',');
            st.append("{\"t\":").append(q(text)).append(",\"at\":").append(at).append(",\"d\":").append(Math.round(endM - cum[at])).append('}');
        }
        if (stepsOut++ > 0) st.append(',');
        st.append("{\"t\":\"Arrive at your destination\",\"at\":").append(N - 1).append(",\"d\":0}");

        StringBuilder out = new StringBuilder(N * 24 + st.length() + 120);
        out.append("{\"ok\":true,\"dist\":").append(Math.round(total)).append(",\"time\":").append(Math.round(secs)).append(",\"pts\":[");
        for (int i = 0; i < N; i++) {
            if (i > 0) out.append(',');
            out.append(Math.round(pts.get(i)[0] * 1e5) / 1e5).append(',').append(Math.round(pts.get(i)[1] * 1e5) / 1e5);
        }
        out.append("],\"steps\":[").append(st).append("]}");
        return out.toString();
    }

    private String nm(Piece p) { String n = name(p.name); return n.isEmpty() ? null : n; }

    private String maneuver(Piece before, Piece nowP, double ang, ArrayList<int[]> runs, int r, ArrayList<Piece> pieces) {
        String onto = nm(nowP) == null ? "" : " onto " + nm(nowP);
        String side = ang > 0 ? "right" : "left";
        double a = Math.abs(ang);
        if (nowP.rb == 1) return "Enter the roundabout";
        if (before.rb == 1) {
            int k = 0; int[] prevRun = runs.get(r - 1);
            for (int i = prevRun[0] + 1; i <= prevRun[1]; i++) { Piece p = pieces.get(i); if (p.node >= 0 && I(oFirst, p.node + 1) - I(oFirst, p.node) >= 2) k++; }
            k += 1;
            String[] ord = {"", "1st", "2nd", "3rd", "4th", "5th", "6th"};
            return "At the roundabout, take the " + (k < ord.length ? ord[k] : k + "th") + " exit" + onto;
        }
        boolean nowLink = isLink(nowP.cls), beforeLink = isLink(before.cls);
        if (nowLink && !beforeLink) return (before.cls == 0 || before.cls == 2) ? "Take the exit on the " + side + onto : "Take the ramp" + onto;
        if ((nowP.cls == 0 || nowP.cls == 2) && (beforeLink || before.cls > 3) && a < 60) return "Merge" + onto;
        if (a >= 170) return "Make a U-turn";
        if (a < 20) return "Continue" + onto;
        if (a < 45) return "Bear " + side + onto;
        if (a < 135) return "Turn " + side + onto;
        return "Make a sharp " + side + onto;
    }

    private static boolean isLink(int c) { return c == 1 || c == 3 || c == 5 || c == 7 || c == 9; }

    private int nameOfSeg(int seg) { int e = edgeOf(seg, 0); if (e < 0) e = edgeOf(seg, 1); return e < 0 ? 0 : I(oName, e); }
    private int clsOfSeg(int seg) { int e = edgeOf(seg, 0); if (e < 0) e = edgeOf(seg, 1); return e < 0 ? 11 : (I(oMeta, e) >> 8) & 0xFF; }
    private int rbOfSeg(int seg) { int e = edgeOf(seg, 0); if (e < 0) e = edgeOf(seg, 1); return e < 0 ? 0 : (I(oMeta, e) >> 16) & 1; }

    private int fromNodeOfEdge(int e) {
        // Edges are sorted by their start node: binary search the CSR offsets.
        int lo = 0, hi = nN - 1;
        while (lo < hi) { int mid = (lo + hi + 1) >>> 1; if (I(oFirst, mid) <= e) lo = mid; else hi = mid - 1; }
        return lo;
    }

    // Adds shape points of a segment from index a to index z (inclusive), forward or backward.
    private void appendRange(ArrayList<double[]> pts, int seg, int a, int z, boolean forward) {
        if (forward) { for (int k = a; k <= z; k++) pts.add(new double[]{ptLat(seg, k), ptLon(seg, k)}); }
        else { for (int k = a; k >= z; k--) pts.add(new double[]{ptLat(seg, k), ptLon(seg, k)}); }
    }

    private static double brg(double[] a, double[] c) { return (Math.toDegrees(Math.atan2((c[1] - a[1]) * KX, (c[0] - a[0]) * KY)) + 360) % 360; }
    // Direction of travel just before (in=true looks backward from i) or just after point i, over about 25 m.
    private double bearing(ArrayList<double[]> pts, double[] cum, int i, boolean after) {
        int j = i;
        if (after) { while (j + 1 < pts.size() && cum[j] - cum[i] < 25) j++; return brg(pts.get(i), pts.get(Math.max(j, Math.min(i + 1, pts.size() - 1)))); }
        while (j > 0 && cum[i] - cum[j] < 25) j--;
        return brg(pts.get(Math.min(j, Math.max(i - 1, 0))), pts.get(i));
    }
    private double turnAngle(ArrayList<double[]> pts, double[] cum, int i) {
        double in = bearing(pts, cum, i, false), out = bearing(pts, cum, i, true);
        return ((out - in) % 360 + 540) % 360 - 180;
    }
    private static String compass(double b) { String[] n = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"}; return n[(int) Math.round(b / 45) % 8]; }
    private static String q(String s) {
        StringBuilder x = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c == '"' || c == '\\') x.append('\\').append(c); else if (c < 32) x.append(' '); else x.append(c); }
        return x.append('"').toString();
    }
}
