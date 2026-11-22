package com.highlanderdash.tiremonitor;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Per-sensor measurements for comparing tire sensor sets: when each sensor was first heard, how often it speaks
 * (driving and parked, typical and worst case), how much of the time a reading was fresh, signal strength and
 * steadiness, the smallest pressure step it reports, and a one-row-per-minute log.
 * A "broadcast" is a burst of packets: a packet more than 3 seconds after the previous one starts a new burst, so a
 * sensor that repeats the same message several times in a row still counts once.
 * Plain Java (no Android classes) so it can be tested off the phone; the caller passes in the clock.
 */
final class TireStats {
    static final long BURST_GAP_MS = 3000;
    static final long FRESH_MS = 120000;   // a reading counts as fresh for 2 minutes
    private static final long MINUTE_MS = 60000;
    private static final int MAX_INTERVALS = 600;
    private static final int KEEP_MINUTES = 1440;
    static final String CSV_HEADER = "time,window_start,wheel,mac,set,packets,broadcasts,signal_avg,signal_min,signal_max,driving,psi,temp_c";

    private static final class Bucket {
        long minute;            // wall clock minute number
        int packets, bursts;
        long rssiSum;
        int rssiMin = 0, rssiMax = 0;
        boolean moving;
        double psi;
        int tempC;
        String wheel = "";
        boolean drained;
    }

    private static final class Mode {
        final ArrayList<Long> iv = new ArrayList<>();   // times between broadcast starts
        long ms, fresh;                                  // time spent in this mode, and the part of it with a fresh reading
    }

    private static final class S {
        String kind;
        long first = -1;        // ms from the window start to the first packet
        long last = -1;         // clock time of the latest packet
        long burstStart = -1;   // clock time the current burst began
        long intervalSum = 0;
        int intervals = 0;
        int bursts = 0, packets = 0;
        long maxGap = 0;        // longest silence between two packets
        long rssiSum = 0;
        double rssiSq = 0;
        int rssiMin = 0, rssiMax = 0, rssiLast = 0;
        long lastPa = -1, minStepPa = -1;
        int changes = 0;
        final Mode move = new Mode(), park = new Mode();
        final TreeMap<Long, Bucket> buckets = new TreeMap<>();
    }

    private final Map<String, S> map = new HashMap<>();
    private final Map<String, String> wheels = new HashMap<>();
    private final long wallBase;    // wall clock time minus the elapsed clock, so elapsed times map to wall times
    private long start;
    private long lastTick = -1;
    private long winMove = 0, winPark = 0;

    TireStats(long nowElapsed, long nowWall) {
        start = nowElapsed;
        wallBase = nowWall - nowElapsed;
    }

    /** Which wheel each sensor address is assigned to ("FL", "FR", "RL", "RR"); stamped on the log rows. */
    synchronized void setWheels(Map<String, String> w) {
        wheels.clear();
        wheels.putAll(w);
    }

    /** Called about once a second: tracks how long the car has been driving and how much of that each sensor was fresh. */
    synchronized void tick(long now, boolean moving) {
        if (lastTick < 0) { lastTick = now; return; }
        long dt = now - lastTick;
        lastTick = now;
        if (dt <= 0 || dt > 30000) return;   // a pause (the unit asleep) is not counted
        if (moving) winMove += dt; else winPark += dt;
        for (S s : map.values()) {
            Mode m = moving ? s.move : s.park;
            m.ms += dt;
            if (s.last >= 0 && now - s.last <= FRESH_MS) m.fresh += dt;
        }
    }

    /** Closes the window: returns the log rows not written yet, then starts a new window from now. */
    synchronized List<String> reset(long now) {
        List<String> rows = pending(now, true);
        map.clear();
        start = now;
        winMove = 0;
        winPark = 0;
        return rows;
    }

    /**
     * @param kind "l" for the LeePee set, "n" for the second set
     * @param pa pressure in pascals, tempCx100 temperature in hundredths of a degree C
     */
    synchronized void record(String mac, String kind, long now, int rssi, long pa, int tempCx100, boolean moving) {
        S s = map.get(mac);
        // Android reports 127 when it has no signal reading for a packet. Counting it would wreck the averages, so keep the last real value.
        if (rssi >= 0 || rssi < -127) rssi = s != null ? s.rssiLast : -100;
        boolean newBurst = false;
        if (s == null) {
            s = new S();
            s.kind = kind;
            s.first = now - start;
            s.burstStart = now;
            s.bursts = 1;
            s.move.ms = winMove;   // a sensor heard late still counts the time before it spoke
            s.park.ms = winPark;
            s.rssiMin = rssi;
            s.rssiMax = rssi;
            map.put(mac, s);
            newBurst = true;
        } else {
            long gap = now - s.last;
            if (gap > s.maxGap) s.maxGap = gap;
            if (gap > BURST_GAP_MS) {
                long iv = now - s.burstStart;
                s.intervalSum += iv;
                s.intervals++;
                ArrayList<Long> list = (moving ? s.move : s.park).iv;
                list.add(iv);
                if (list.size() > MAX_INTERVALS) list.remove(0);
                s.burstStart = now;
                s.bursts++;
                newBurst = true;
            }
            if (rssi < s.rssiMin) s.rssiMin = rssi;
            if (rssi > s.rssiMax) s.rssiMax = rssi;
        }
        s.last = now;
        s.packets++;
        s.rssiSum += rssi;
        s.rssiSq += (double) rssi * rssi;
        s.rssiLast = rssi;
        if (s.lastPa >= 0 && pa != s.lastPa) {
            s.changes++;
            long step = Math.abs(pa - s.lastPa);
            if (s.minStepPa < 0 || step < s.minStepPa) s.minStepPa = step;
        }
        s.lastPa = pa;

        long minute = (now + wallBase) / MINUTE_MS;
        Bucket b = s.buckets.get(minute);
        if (b == null) {
            b = new Bucket();
            b.minute = minute;
            b.rssiMin = rssi;
            b.rssiMax = rssi;
            s.buckets.put(minute, b);
            while (s.buckets.size() > KEEP_MINUTES) s.buckets.pollFirstEntry();
        }
        b.packets++;
        if (newBurst) b.bursts++;
        b.rssiSum += rssi;
        if (rssi < b.rssiMin) b.rssiMin = rssi;
        if (rssi > b.rssiMax) b.rssiMax = rssi;
        if (moving) b.moving = true;
        b.psi = pa / 6894.757;
        b.tempC = (int) Math.round(tempCx100 / 100.0);
        String w = wheels.get(mac);
        b.wheel = w == null ? "" : w;
    }

    // ---------- Output ----------

    private static long pct(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return -1;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private static void modeJson(StringBuilder b, String key, Mode m) {
        ArrayList<Long> sorted = new ArrayList<>(m.iv);
        Collections.sort(sorted);
        double cov = m.ms > 0 ? (double) m.fresh / m.ms : -1;
        b.append(",\"").append(key).append("\":{\"n\":").append(sorted.size())
            .append(",\"p50\":").append(pct(sorted, 0.5))
            .append(",\"p90\":").append(pct(sorted, 0.9))
            .append(",\"max\":").append(sorted.isEmpty() ? -1 : sorted.get(sorted.size() - 1))
            .append(",\"ms\":").append(m.ms)
            .append(",\"cov\":").append(cov < 0 ? "-1" : String.format(Locale.US, "%.3f", cov)).append('}');
    }

    /**
     * {"window":ms,"start":wall ms,"mvMs":ms driving,"pkMs":ms parked,"s":{"AA:BB:..":{...}}}
     * Per sensor: kind, first, packets, bursts, avg (mean time between broadcasts, -1 until there are two), maxGap
     * (includes a silence still going on), ago, rssi (average), rssiMin, rssiMax, rssiSd, rssiLast, changes, stepPa,
     * and mv / pk (driving / parked): n intervals, p50, p90, max, ms spent, cov (share of that time with a fresh reading).
     */
    synchronized String json(long now) {
        StringBuilder b = new StringBuilder();
        b.append("{\"window\":").append(now - start).append(",\"start\":").append(start + wallBase)
            .append(",\"mvMs\":").append(winMove).append(",\"pkMs\":").append(winPark).append(",\"s\":{");
        boolean firstItem = true;
        for (Map.Entry<String, S> e : map.entrySet()) {
            S s = e.getValue();
            if (!firstItem) b.append(',');
            firstItem = false;
            long ago = now - s.last;
            long maxGap = Math.max(s.maxGap, ago);
            long avg = s.intervals > 0 ? s.intervalSum / s.intervals : -1;
            double mean = s.packets > 0 ? (double) s.rssiSum / s.packets : 0;
            double sd = s.packets > 0 ? Math.sqrt(Math.max(0, s.rssiSq / s.packets - mean * mean)) : 0;
            b.append('"').append(e.getKey()).append("\":{\"kind\":\"").append(s.kind).append('"')
                .append(",\"first\":").append(s.first)
                .append(",\"packets\":").append(s.packets)
                .append(",\"bursts\":").append(s.bursts)
                .append(",\"avg\":").append(avg)
                .append(",\"maxGap\":").append(maxGap)
                .append(",\"ago\":").append(ago)
                .append(",\"rssi\":").append(Math.round(mean))
                .append(",\"rssiMin\":").append(s.rssiMin)
                .append(",\"rssiMax\":").append(s.rssiMax)
                .append(",\"rssiSd\":").append(String.format(Locale.US, "%.1f", sd))
                .append(",\"rssiLast\":").append(s.rssiLast)
                .append(",\"changes\":").append(s.changes)
                .append(",\"stepPa\":").append(s.minStepPa);
            modeJson(b, "mv", s.move);
            modeJson(b, "pk", s.park);
            b.append('}');
        }
        return b.append("}}").toString();
    }

    /**
     * The last few minutes for the strip chart: {"cur":current wall minute,"s":{"AA:BB:..":[[minute,packets,signalAvg,driving],...]}}
     */
    synchronized String timeline(long now, int minutes) {
        long cur = (now + wallBase) / MINUTE_MS;
        StringBuilder b = new StringBuilder();
        b.append("{\"cur\":").append(cur).append(",\"s\":{");
        boolean firstItem = true;
        for (Map.Entry<String, S> e : map.entrySet()) {
            if (!firstItem) b.append(',');
            firstItem = false;
            b.append('"').append(e.getKey()).append("\":[");
            boolean f = true;
            for (Bucket k : e.getValue().buckets.tailMap(cur - minutes + 1, true).values()) {
                if (!f) b.append(',');
                f = false;
                b.append('[').append(k.minute).append(',').append(k.packets).append(',')
                    .append(Math.round((double) k.rssiSum / Math.max(1, k.packets))).append(',').append(k.moving ? 1 : 0).append(']');
            }
            b.append(']');
        }
        return b.append("}}").toString();
    }

    private String line(String mac, S s, Bucket k) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        long wallStart = start + wallBase;
        return f.format(new Date(k.minute * MINUTE_MS)) + "," + f.format(new Date(wallStart)) + "," + k.wheel + "," + mac + ","
            + ("n".equals(s.kind) ? "new" : "leepee") + "," + k.packets + "," + k.bursts + ","
            + Math.round((double) k.rssiSum / Math.max(1, k.packets)) + "," + k.rssiMin + "," + k.rssiMax + ","
            + (k.moving ? 1 : 0) + "," + String.format(Locale.US, "%.1f", k.psi) + "," + k.tempC;
    }

    private List<String> pending(long now, boolean includeCurrent) {
        long cur = (now + wallBase) / MINUTE_MS;
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, S> e : map.entrySet()) {
            for (Bucket k : e.getValue().buckets.values()) {
                if (k.drained || (!includeCurrent && k.minute >= cur)) continue;
                rows.add(line(e.getKey(), e.getValue(), k));
                if (includeCurrent || k.minute < cur) k.drained = true;
            }
        }
        Collections.sort(rows);
        return rows;
    }

    /** Log rows for minutes that have ended and were not handed out yet (the caller writes them to a file). */
    synchronized List<String> drainClosed(long now) { return pending(now, false); }

    /** Log rows for the minute still open, without marking them written (so a copy of the log includes them). */
    synchronized List<String> openRows(long now) {
        long cur = (now + wallBase) / MINUTE_MS;
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, S> e : map.entrySet())
            for (Bucket k : e.getValue().buckets.values())
                if (!k.drained && k.minute >= cur) rows.add(line(e.getKey(), e.getValue(), k));
        Collections.sort(rows);
        return rows;
    }
}
