package com.highlanderdash.tiremonitor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens for the tire sensors, keeps the timing numbers and the per-minute log, and watches GPS speed
 * (to tell driving from parked). It runs as a foreground service so Android keeps it going with the screen off.
 * Everything it knows lives in static fields, so the screen can come and go without losing any of it.
 */
public class TireService extends Service {

    /** Receives a line of JavaScript for the page while the screen is open. */
    public interface Listener { void js(String code); }

    private static final String FBB0 = "0000fbb0-0000-1000-8000-00805f9b34fb";
    private static final String CHANNEL = "tires";

    static volatile Listener listener;
    static volatile boolean running = false;
    static volatile String status = "starting";
    static volatile int heard = 0, packets = 0;
    /** The newest raw sensor packets (kept in memory only), so the bytes can be studied: time, sensor, signal, hex. */
    private static final java.util.ArrayDeque<String> rawBuf = new java.util.ArrayDeque<>();
    private static final java.text.SimpleDateFormat RAW_FMT = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US);
    static void rawAdd(String kind, String mac, int rssi, byte[] data) {
        StringBuilder h = new StringBuilder();
        for (byte b : data) h.append(String.format("%02X", b & 0xff));
        String row;
        synchronized (rawBuf) { row = RAW_FMT.format(new java.util.Date()) + "," + kind + "," + mac + "," + rssi + "," + h; rawBuf.add(row); while (rawBuf.size() > 1500) rawBuf.poll(); }
    }
    static String rawText() {
        StringBuilder b = new StringBuilder("time,set(n=new l=leepee),sensor,rssi,hex_after_company_id\n");
        synchronized (rawBuf) { for (String r : rawBuf) b.append(r).append('\n'); }
        return b.toString();
    }
    static void rawClear() { synchronized (rawBuf) { rawBuf.clear(); } }
    static volatile long lastMovingAt = -100000;

    static final TireStats stats = new TireStats(SystemClock.elapsedRealtime(), System.currentTimeMillis());
    /** Newest reading per sensor: {pa, centiC, battery, flag, rssi, kind, elapsed time heard}. */
    static final HashMap<String, Object[]> latest = new HashMap<>();
    private static final HashMap<String, Long> sent = new HashMap<>();
    private static final Object logLock = new Object();
    private static File logFile;

    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private LocationManager lm;
    private long lastFlush = 0;
    private final Runnable restart = this::startScan;

    private final ScanCallback callback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { handle(r); }
        @Override public void onBatchScanResults(List<ScanResult> list) { for (ScanResult r : list) handle(r); }
        @Override public void onScanFailed(int code) { status = "failed " + code; }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            if (l != null && l.hasSpeed() && l.getSpeed() >= 1.5f) lastMovingAt = SystemClock.elapsedRealtime();
        }
        @Override public void onStatusChanged(String provider, int s, Bundle extras) { }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
    };

    /** Once a second: driving versus parked time, and every 30 s the finished minutes go to the log file. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            stats.tick(now, isMoving(now));
            if (now - lastFlush > 30000) { lastFlush = now; appendLog(stats.drainClosed(now)); }
            main.postDelayed(this, 1000);
        }
    };

    static boolean isMoving(long now) { return now - lastMovingAt < 6000; }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        logFile = new File(getFilesDir(), "tire_log.csv");
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Tire sensors", NotificationManager.IMPORTANCE_LOW));
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Tire Monitor")
                    .setContentText("Listening for tire sensors")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(1, n);
        } catch (Exception e) {
            // Not allowed to run in the foreground right now: carry on only while the screen is open.
            status = "failed " + e.getClass().getSimpleName();
        }
        if (!running) {
            running = true;
            lastFlush = SystemClock.elapsedRealtime();
            main.removeCallbacks(tick);
            main.postDelayed(tick, 1000);
        }
        startScan();
        startLocation();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacks(tick);
        stopScan();
        stopLocation();
        appendLog(stats.drainClosed(SystemClock.elapsedRealtime()));
        super.onDestroy();
    }

    private boolean hasPerm(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        main.removeCallbacks(restart);
        try { if (scanner != null) scanner.stopScan(callback); } catch (Exception ignored) { }
        scanner = null;
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        stopScan();
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) || (Build.VERSION.SDK_INT >= 31 && !hasPerm(Manifest.permission.BLUETOOTH_SCAN))) {
            status = "noperm";
            return;
        }
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) { status = "nobt"; return; }
            if (!a.isEnabled()) { status = "off"; main.postDelayed(restart, 5000); return; }
            LocationManager l = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 28 && l != null && !l.isLocationEnabled()) { status = "locoff"; main.postDelayed(restart, 5000); return; }
            scanner = a.getBluetoothLeScanner();
            if (scanner == null) { status = "off"; main.postDelayed(restart, 5000); return; }
            ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(null, st, callback);
            status = "ok";
            // Android quietly weakens a scan that runs past 30 minutes, so restart it every 20.
            main.postDelayed(restart, 20 * 60 * 1000);
        } catch (Exception e) { status = "failed"; }
    }

    @SuppressLint("MissingPermission")
    private void startLocation() {
        stopLocation();
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) return;
        try {
            lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, locationListener, Looper.getMainLooper());
        } catch (Exception ignored) { }
    }

    private void stopLocation() {
        try { if (lm != null) lm.removeUpdates(locationListener); } catch (Exception ignored) { }
        lm = null;
    }

    private void handle(ScanResult res) {
        heard++;
        ScanRecord rec = res.getScanRecord();
        if (rec == null) return;
        byte[] nd = rec.getManufacturerSpecificData(TireDecoder.COMPANY);   // second sensor set
        byte[] d = rec.getManufacturerSpecificData(0x0100);                 // LeePee set
        if (nd == null && (d == null || d.length < 16)) return;
        boolean tire = false;
        List<ParcelUuid> uuids = rec.getServiceUuids();
        if (uuids != null) for (ParcelUuid u : uuids) if (FBB0.equalsIgnoreCase(u.toString())) tire = true;
        String name = rec.getDeviceName();
        if (name != null && name.startsWith("TPMS")) tire = true;
        if (!tire) return;
        packets++;
        long now = SystemClock.elapsedRealtime();
        int rssi = res.getRssi();
        String mac, kind;
        long pa;
        int c, batt, flag;
        if (nd != null) {
            TireDecoder.Reading r = TireDecoder.decode(nd);
            if (r == null) return;
            mac = r.mac; kind = "n"; pa = r.pa; c = r.tempC * 100; batt = (int) -Math.round(r.volts * 100); flag = 0;   // negative = minus the centivolts
        } else {
            mac = res.getDevice().getAddress(); kind = "l";
            pa = (d[6] & 0xffL) | (d[7] & 0xffL) << 8 | (d[8] & 0xffL) << 16 | (d[9] & 0xffL) << 24;
            c = (d[10] & 0xff) | (d[11] & 0xff) << 8 | (d[12] & 0xff) << 16 | d[13] << 24;
            batt = d[14] & 0xff; flag = d[15] & 0xff;
        }
        rawAdd(kind, mac, rssi, nd != null ? nd : d);
        stats.record(mac, kind, now, rssi, pa, c, isMoving(now));
        synchronized (sent) {
            latest.put(mac, new Object[]{pa, c, batt, flag, rssi, kind, now});
            Long last = sent.get(mac);
            if (last != null && now - last < 2000) return;
            sent.put(mac, now);
        }
        Listener l = listener;
        if (l != null) l.js(readingJs(mac, pa, c, batt, flag, rssi, kind, 0));
    }

    static String readingJs(String mac, long pa, int c, int batt, int flag, int rssi, String kind, long ageMs) {
        return "window.onTire && onTire('" + mac + "'," + pa + "," + c + "," + batt + "," + flag + "," + rssi + ",'" + kind + "'," + ageMs + ")";
    }

    /** One line of JavaScript per sensor heard so far, so a page that was closed catches up when it opens. */
    static List<String> replay() {
        List<String> out = new ArrayList<>();
        long now = SystemClock.elapsedRealtime();
        synchronized (sent) {
            for (Map.Entry<String, Object[]> e : latest.entrySet()) {
                Object[] v = e.getValue();
                out.add(readingJs(e.getKey(), (Long) v[0], (Integer) v[1], (Integer) v[2], (Integer) v[3], (Integer) v[4], (String) v[5], now - (Long) v[6]));
            }
        }
        return out;
    }

    // ---------- Per-minute log file ----------

    static void appendLog(List<String> rows) {
        if (rows == null || rows.isEmpty() || logFile == null) return;
        synchronized (logLock) {
            try {
                File f = logFile;
                if (f.length() > 2000000) {
                    ArrayList<String> keep = new ArrayList<>();
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        String ln; while ((ln = r.readLine()) != null) keep.add(ln);
                    }
                    f.delete();
                    try (FileWriter w = new FileWriter(f, true)) {
                        w.write(TireStats.CSV_HEADER + "\n");
                        for (int i = Math.max(1, keep.size() / 2); i < keep.size(); i++) w.write(keep.get(i) + "\n");
                    }
                }
                boolean fresh = !f.exists() || f.length() == 0;
                try (FileWriter w = new FileWriter(f, true)) {
                    if (fresh) w.write(TireStats.CSV_HEADER + "\n");
                    for (String row : rows) w.write(row + "\n");
                }
            } catch (Exception ignored) { }
        }
    }

    /** The newest rows of the log file plus the minute still open, ready to copy. */
    static String readLog(File dir, int maxRows) {
        ArrayDeque<String> tail = new ArrayDeque<>();
        synchronized (logLock) {
            try {
                File f = new File(dir, "tire_log.csv");
                if (f.exists()) try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String ln; r.readLine();
                    while ((ln = r.readLine()) != null) { tail.addLast(ln); if (tail.size() > maxRows) tail.removeFirst(); }
                }
            } catch (Exception ignored) { }
        }
        for (String row : stats.openRows(SystemClock.elapsedRealtime())) { tail.addLast(row); if (tail.size() > maxRows) tail.removeFirst(); }
        StringBuilder b = new StringBuilder(TireStats.CSV_HEADER).append('\n');
        for (String row : tail) b.append(row).append('\n');
        return b.toString();
    }

    static void clearLog(File dir) {
        synchronized (logLock) { new File(dir, "tire_log.csv").delete(); }
    }
}
