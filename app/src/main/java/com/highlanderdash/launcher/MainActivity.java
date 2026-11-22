package com.highlanderdash.launcher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.KeyEvent;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private WebView web;
    private LocationManager locationManager;
    private MediaSessionManager mediaSessionManager;
    private MediaController controller;
    private RadioProbe probe = null;
    private boolean radioWasSource = false;
    private FytRadio fyt;
    private String lastPlayingPkg = null;   // the app that was last seen playing; it stays the target while paused
    private void note(String t) { if (probe != null) probe.note(t); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private String appsJson = null;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFF08080A);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Car");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        hideSystemBars();

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        probe = new RadioProbe(this);   // only logs notes until the diagnostic is opened
        fyt = new FytRadio(this, new FytRadio.Listener() {
            @Override public void onChanged() { pushMedia(); }
            @Override public void onNote(String t) { note(t); }
        });
        fyt.ensure();

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
        new Thread(this::buildAppList).start();
        startTires();
        main.postDelayed(tireTick, 1000);

        // FYT units often wake from ignition-off without a real boot, so also
        // catch the screen turning back on while our process is still alive.
        IntentFilter wake = new IntentFilter();
        wake.addAction(Intent.ACTION_SCREEN_ON);
        wake.addAction(Intent.ACTION_USER_PRESENT);
        getApplicationContext().registerReceiver(new BootReceiver(), wake);
    }

    @Override
    protected void onResume() {
        super.onResume();
        note("APP launcher resumed");
        // The unit drops the radio as audio source when the launcher comes forward. If the radio was the source
        // when we left, claim it back once things settle (only if nothing else took over meanwhile).
        if (fyt != null && fyt.appId == 1) radioWasSource = true;
        final boolean reclaim = radioWasSource;
        radioWasSource = false;
        if (reclaim) main.postDelayed(() -> {
            if (fyt != null && fyt.appId == 0) { note("APP radio was dropped on resume, claiming it back"); fyt.claim(); }
        }, 900);
        hideSystemBars();
        startLocation();
        startMedia();
        if (fyt != null) fyt.ensure();
        if (!"ok".equals(tireStatus)) startTires();
        js("window.onResumeFromAndroid && onResumeFromAndroid()");
    }

    @Override
    protected void onPause() {
        super.onPause();
        note("APP launcher paused");
        radioWasSource = fyt != null && fyt.appId == 1;
        try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        startLocation();
        startTires();
    }

    // A home screen should never "exit" on Back; let the page close the app drawer instead.
    @Override
    public void onBackPressed() {
        js("window.onBack && onBack()");
    }

    // Pressing Home while already home also closes the drawer.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        js("window.onBack && onBack()");
    }

    private void hideSystemBars() {
        // Keep the head unit's nav bar visible (it holds Home/Back/volume on FYT units),
        // only hide the status bar since the launcher shows its own clock.
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }



    // ----- spoken directions -----
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingSpeech = null;
    private AudioFocusRequest focus;
    private final AudioAttributes navAudio = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();

    private void speakNow(String text) {
        if (tts == null) {
            pendingSpeech = text;
            tts = new TextToSpeech(this, status -> {
                if (status != TextToSpeech.SUCCESS) return;
                tts.setLanguage(Locale.US);
                tts.setAudioAttributes(navAudio);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { dropFocus(); }
                    @Override public void onError(String id) { dropFocus(); }
                });
                ttsReady = true;
                if (pendingSpeech != null) { say(pendingSpeech); pendingSpeech = null; }
            });
            return;
        }
        if (ttsReady) say(text);
    }

    private void say(String text) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(navAudio).build();
            am.requestAudioFocus(focus);
        } catch (Exception ignored) { }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav");
    }

    private void dropFocus() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (focus != null) am.abandonAudioFocusRequest(focus);
        } catch (Exception ignored) { }
    }

    private Router router;

    private int versionCodeNow() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }
    private final Object routerLock = new Object();

    /** Maps the road graph into memory: straight from the APK when it's stored uncompressed, else from a copy in app storage. */
    private MappedByteBuffer openGraph() throws Exception {
        try (android.content.res.AssetFileDescriptor afd = getAssets().openFd("route/graph.rg");
             FileInputStream in = afd.createInputStream()) {
            return in.getChannel().map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getLength());
        } catch (java.io.FileNotFoundException notStored) {
            File f = new File(getFilesDir(), "graph_" + versionCodeNow() + ".rg");
            try (InputStream in = getAssets().open("route/graph.rg")) {
                if (!f.exists() || f.length() < 1000) {
                    try (FileOutputStream o = new FileOutputStream(f)) {
                        byte[] buf = new byte[1 << 16]; int n;
                        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    }
                }
            }
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) { return r.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, r.length()); }
        }
    }

    private void js(String code) {
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    // ---------- GPS: speed, elevation, location for sunrise/sunset ----------

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { pushLocation(l); }
        @Override public void onStatusChanged(String p, int st, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    @SuppressLint("MissingPermission")
    private void startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) pushLocation(last);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener);
        } catch (Exception ignored) { }
    }

    private double lastPlaceLat = 999, lastPlaceLon = 999;

    /** Town name for the map card, looked up at most every ~1.5 miles. Needs internet; fails quietly offline. */
    private void maybeLookupPlace(double lat, double lon) {
        if (Math.abs(lat - lastPlaceLat) < 0.02 && Math.abs(lon - lastPlaceLon) < 0.02) return;
        if (!Geocoder.isPresent()) return;
        lastPlaceLat = lat; lastPlaceLon = lon;
        new Thread(() -> {
            try {
                List<Address> r = new Geocoder(this, Locale.US).getFromLocation(lat, lon, 1);
                if (r == null || r.isEmpty()) return;
                Address a = r.get(0);
                String town = a.getLocality() != null ? a.getLocality() : a.getSubAdminArea();
                String state = a.getAdminArea();
                if (town == null) return;
                JSONObject o = new JSONObject();
                o.put("town", town);
                o.put("state", state == null ? "" : stateAbbr(state));
                js("window.onPlace && onPlace(" + o + ")");
            } catch (Exception e) { lastPlaceLat = 999; }
        }).start();
    }

    private static String stateAbbr(String s) {
        return "Colorado".equals(s) ? "CO" : s;
    }

    private void pushLocation(Location l) {
        maybeLookupPlace(l.getLatitude(), l.getLongitude());
        double speed = l.hasSpeed() ? l.getSpeed() : -1;
        if (speed >= 1.5) lastMovingAt = SystemClock.elapsedRealtime();
        double alt = l.hasAltitude() ? l.getAltitude() : -99999;
        double bearing = l.hasBearing() ? l.getBearing() : -1;
        double acc = l.hasAccuracy() ? l.getAccuracy() : -1;
        js("window.onLocation && onLocation(" + l.getLatitude() + "," + l.getLongitude() + "," + speed + "," + alt + "," + bearing + "," + acc + ")");
    }

    // ---------- Now playing (radio, Spotify, Bluetooth audio) ----------

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { pushMedia(); }
        @Override public void onPlaybackStateChanged(PlaybackState st) {
            if (controller != null) {
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) lastPlayingPkg = controller.getPackageName();
                note("MEDIA " + controller.getPackageName() + " state=" + (st == null ? "none" : st.getState()));
            }
            pushMedia();
        }
        @Override public void onSessionDestroyed() { controller = null; pushMedia(); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::pickController;

    private boolean hasMediaAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private void startMedia() {
        if (!hasMediaAccess()) { pushMedia(); return; }
        try {
            ComponentName cn = new ComponentName(this, MediaListener.class);
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsListener, cn);
            pickController(mediaSessionManager.getActiveSessions(cn));
        } catch (Exception e) {
            pushMedia();
        }
    }

    private void pickController(List<MediaController> list) {
        if (controller != null) controller.unregisterCallback(mediaCallback);
        controller = null;
        if (list == null) list = Collections.emptyList();
        // Prefer whatever is actually playing; then the app that last played (so a paused Spotify
        // stays the target even if another app's session is listed first); then the first session.
        for (MediaController c : list) {
            PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { controller = c; lastPlayingPkg = c.getPackageName(); break; }
        }
        if (controller == null && lastPlayingPkg != null) {
            for (MediaController c : list) if (lastPlayingPkg.equals(c.getPackageName())) { controller = c; break; }
        }
        if (controller == null && !list.isEmpty()) controller = list.get(0);
        StringBuilder sn = new StringBuilder("MEDIA sessions:");
        for (MediaController c : list) { PlaybackState st = c.getPlaybackState(); sn.append(' ').append(c.getPackageName()).append('=').append(st == null ? "none" : st.getState()); }
        note(sn + " -> using " + (controller == null ? "none" : controller.getPackageName()));
        if (controller != null) controller.registerCallback(mediaCallback, main);
        pushMedia();
    }

    private String mediaJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("access", hasMediaAccess());
            if (controller != null) {
                MediaMetadata m = controller.getMetadata();
                PlaybackState st = controller.getPlaybackState();
                String title = m != null ? m.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
                String artist = m != null ? m.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
                if (artist == null && m != null) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
                o.put("title", title == null ? "" : title);
                o.put("artist", artist == null ? "" : artist);
                o.put("playing", st != null && st.getState() == PlaybackState.STATE_PLAYING);
                o.put("source", appLabel(controller.getPackageName()));
            }
        } catch (Exception ignored) { }
        try {
            if (fyt != null && fyt.isOn()) {
                JSONObject r = new JSONObject();
                r.put("freq", fyt.freqText());
                r.put("band", fyt.presetText());
                r.put("name", fyt.station());
                r.put("text", fyt.text());
                o.put("radio", r);
            }
        } catch (Exception ignored) { }
        return o.toString();
    }

    /** Copies a file into the public Download folder (MediaStore on Android 10 and up). */
    private String copyToDownloads(java.io.File src, String name) {
        try (InputStream in = new java.io.FileInputStream(src)) {
            java.io.OutputStream os;
            String where;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/");
                android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return "could not create file";
                os = getContentResolver().openOutputStream(uri);
                where = "Download/" + name;
            } else {
                java.io.File dst = new java.io.File(getExternalFilesDir(null), name);
                os = new java.io.FileOutputStream(dst);
                where = dst.getAbsolutePath();
            }
            long total = 0;
            try (java.io.OutputStream o = os) {
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) { o.write(buf, 0, r); total += r; }
            }
            return "saved to " + where + " (" + (total / 1024) + " KB)";
        } catch (Exception e) { return "failed: " + e; }
    }

    private void pushMedia() {
        js("window.onMedia && onMedia(" + mediaJson() + ")");
        if (fyt != null) js("window.onRadio && onRadio(" + fyt.stateJson() + ")");
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return pkg; }
    }

    // ---------- App drawer ----------

    private void buildAppList() {
        try {
            PackageManager pm = getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
            Collections.sort(list, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
            JSONArray arr = new JSONArray();
            for (ResolveInfo r : list) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                JSONObject o = new JSONObject();
                o.put("label", r.loadLabel(pm).toString());
                o.put("pkg", pkg);
                o.put("cls", r.activityInfo.name);
                o.put("icon", iconData(r.loadIcon(pm)));
                arr.put(o);
            }
            appsJson = arr.toString();
        } catch (Exception e) {
            appsJson = "[]";
        }
        js("window.onAppsReady && onAppsReady()");
    }

    private String iconData(Drawable d) {
        try {
            int size = 96;
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return ""; }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ---------- Tire pressure (Bluetooth valve-cap sensors) ----------
    // The LeePee / "TPMS1_xxxxxx" caps broadcast their readings openly (no pairing): service 0xFBB0,
    // manufacturer data under company id 0x0100 (16 bytes):
    //   0-5 sensor id (matches its Bluetooth address), 6-9 pressure in Pa above atmosphere (little-endian),
    //   10-13 temperature in 0.01 °C (little-endian, signed), 14 battery %, 15 alert flag.

    private BluetoothLeScanner tireScanner;
    private String tireStatus = "starting";
    private final HashMap<String, Long> tireSent = new HashMap<>();
    private volatile int tireHeard = 0, tirePackets = 0;   // shown on the Tires screen for troubleshooting
    private static final String FBB0 = "0000fbb0-0000-1000-8000-00805f9b34fb";
    // Android quietly weakens a scan that runs past 30 minutes, so restart it every 20.
    private final Runnable restartTires = this::startTires;

    private final ScanCallback tireCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { handleTire(r); }
        @Override public void onBatchScanResults(List<ScanResult> list) { for (ScanResult r : list) handleTire(r); }
        @Override public void onScanFailed(int code) { tireStatus = "failed " + code; }
    };

    @SuppressLint("MissingPermission")
    private void startTires() {
        main.removeCallbacks(restartTires);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { tireStatus = "noperm"; return; }
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) { tireStatus = "nobt"; return; }
        if (!a.isEnabled()) { tireStatus = "off"; main.postDelayed(restartTires, 60000); return; }
        try { if (tireScanner != null) tireScanner.stopScan(tireCallback); } catch (Exception ignored) { }
        tireScanner = a.getBluetoothLeScanner();
        if (tireScanner == null) { tireStatus = "off"; main.postDelayed(restartTires, 60000); return; }
        try {
            ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            tireScanner.startScan(null, st, tireCallback);
            tireStatus = "ok";
        } catch (Exception e) { tireStatus = "failed"; }
        main.postDelayed(restartTires, 20 * 60 * 1000);
    }

    // Timing for comparing the two sensor sets (first heard, broadcasts, longest silence, signal); see TireStats.
    private final TireStats tireTiming = new TireStats(SystemClock.elapsedRealtime(), System.currentTimeMillis());
    private volatile long lastMovingAt = -100000;   // elapsed time of the last GPS fix faster than a walk
    private long lastTireFlush = 0;
    private boolean isMoving(long now) { return now - lastMovingAt < 6000; }

    /** Once a second: tracks driving versus parked time for the sensor comparison, and every 30 s writes finished minutes to the log file. */
    private final Runnable tireTick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            tireTiming.tick(now, isMoving(now));
            if (now - lastTireFlush > 30000) { lastTireFlush = now; appendTireLog(tireTiming.drainClosed(now)); }
            main.postDelayed(this, 1000);
        }
    };

    /** One row per sensor per minute, kept across restarts in the app's own files (trimmed to the newest half past 2 MB). */
    private synchronized void appendTireLog(List<String> rows) {
        if (rows == null || rows.isEmpty()) return;
        try {
            File f = new File(getFilesDir(), "tire_log.csv");
            if (f.length() > 2000000) {
                java.util.ArrayList<String> keep = new java.util.ArrayList<>();
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                    String ln; while ((ln = r.readLine()) != null) keep.add(ln);
                }
                f.delete();
                try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                    w.write(TireStats.CSV_HEADER + "\n");
                    for (int i = Math.max(1, keep.size() / 2); i < keep.size(); i++) w.write(keep.get(i) + "\n");
                }
            }
            boolean fresh = !f.exists() || f.length() == 0;
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                if (fresh) w.write(TireStats.CSV_HEADER + "\n");
                for (String row : rows) w.write(row + "\n");
            }
        } catch (Exception ignored) { }
    }

    /** The newest rows of the log file plus the minute still open, ready to copy. */
    private synchronized String readTireLog(int maxRows) {
        java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
        try {
            File f = new File(getFilesDir(), "tire_log.csv");
            if (f.exists()) try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                String ln; r.readLine();
                while ((ln = r.readLine()) != null) { tail.addLast(ln); if (tail.size() > maxRows) tail.removeFirst(); }
            }
        } catch (Exception ignored) { }
        for (String row : tireTiming.openRows(SystemClock.elapsedRealtime())) { tail.addLast(row); if (tail.size() > maxRows) tail.removeFirst(); }
        StringBuilder b = new StringBuilder(TireStats.CSV_HEADER).append('\n');
        for (String row : tail) b.append(row).append('\n');
        return b.toString();
    }

    private void handleTire(ScanResult res) {
        ScanRecord rec = res.getScanRecord();
        tireHeard++;
        if (rec == null) return;
        byte[] nd = rec.getManufacturerSpecificData(TireDecoder.COMPANY);   // second sensor set
        byte[] d = rec.getManufacturerSpecificData(0x0100);                 // LeePee set
        if (nd == null && (d == null || d.length < 16)) return;
        List<ParcelUuid> uuids = rec.getServiceUuids();
        boolean tire = false;
        if (uuids != null) for (ParcelUuid u : uuids) if (FBB0.equalsIgnoreCase(u.toString())) tire = true;
        String name = rec.getDeviceName();
        if (name != null && name.startsWith("TPMS")) tire = true;
        if (!tire) return;
        if (nd != null) { handleNewTire(res, nd); return; }
        tirePackets++;
        String mac = res.getDevice().getAddress();
        long now = SystemClock.elapsedRealtime();
        long pa = (d[6] & 0xffL) | (d[7] & 0xffL) << 8 | (d[8] & 0xffL) << 16 | (d[9] & 0xffL) << 24;
        int c = (d[10] & 0xff) | (d[11] & 0xff) << 8 | (d[12] & 0xff) << 16 | d[13] << 24;
        tireTiming.record(mac, "l", now, res.getRssi(), pa, c, isMoving(now));
        synchronized (tireSent) {
            Long last = tireSent.get(mac);
            if (last != null && now - last < 2000) return;
            tireSent.put(mac, now);
        }
        int batt = d[14] & 0xff, flag = d[15] & 0xff;
        js("window.onTire && onTire('" + mac + "'," + pa + "," + c + "," + batt + "," + flag + "," + res.getRssi() + ")");
    }

    /** The second sensor set: no battery percent or alert flag, so those go to the page as a negative number (minus the centivolts, for example -276 for 2.76 V) and 0. */
    private void handleNewTire(ScanResult res, byte[] nd) {
        TireDecoder.Reading r = TireDecoder.decode(nd);
        if (r == null) return;
        tirePackets++;
        long now = SystemClock.elapsedRealtime();
        tireTiming.record(r.mac, "n", now, res.getRssi(), r.pa, r.tempC * 100, isMoving(now));
        synchronized (tireSent) {
            Long last = tireSent.get(r.mac);
            if (last != null && now - last < 2000) return;
            tireSent.put(r.mac, now);
        }
        js("window.onTire && onTire('" + r.mac + "'," + r.pa + "," + (r.tempC * 100) + "," + (-Math.round(r.volts * 100)) + ",0," + res.getRssi() + ",'n')");
    }

    // ---------- Bridge the page calls ----------

    private class Bridge {
        /** "ok" while the tire scan runs; otherwise why not (noperm, nobt, off, failed). */
        @JavascriptInterface
        public String tireStatus() { return tireStatus; }

        /** "broadcasts heard,tire packets" since the app started. */
        @JavascriptInterface
        public String tireStats() { return tireHeard + "," + tirePackets; }

        @JavascriptInterface
        public void tireRescan() { main.post(MainActivity.this::startTires); }

        /** Per-sensor timing since the window started, as JSON (see TireStats). */
        @JavascriptInterface
        public String tireTiming() { return tireTiming.json(SystemClock.elapsedRealtime()); }

        /** Starts a new timing window from now (a new "leg"); the finished one is written to the log first. */
        @JavascriptInterface
        public void tireTimingReset() { appendTireLog(tireTiming.reset(SystemClock.elapsedRealtime())); }

        /** The last few minutes per sensor for the strip chart, as JSON. */
        @JavascriptInterface
        public String tireTimeline(int minutes) { return tireTiming.timeline(SystemClock.elapsedRealtime(), minutes); }

        /** Which wheel each sensor is on, as {"AA:BB:..":"FL"}; stamped on the log rows. */
        @JavascriptInterface
        public void tireSetWheels(String json) {
            try {
                JSONObject o = new JSONObject(json);
                HashMap<String, String> m = new HashMap<>();
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) { String k = it.next(); m.put(k, o.optString(k)); }
                tireTiming.setWheels(m);
            } catch (Exception ignored) { }
        }

        /** The newest rows of the per-minute log (CSV with a header line), for pasting into a message. */
        @JavascriptInterface
        public String tireLog(int maxRows) { return readTireLog(maxRows); }

        /** Deletes the saved log. */
        @JavascriptInterface
        public void tireLogClear() { synchronized (MainActivity.this) { new File(getFilesDir(), "tire_log.csv").delete(); } }

        /** Opens an app by package name; returns false if it isn't installed. */
        @JavascriptInterface
        public boolean launch(String pkg) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return false;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(i);
                return true;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public boolean launchActivity(String pkg, String cls) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                i.setComponent(new ComponentName(pkg, cls));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(i);
                return true;
            } catch (Exception e) { return launch(pkg); }
        }

        /** Description of the packed road and water map, or "" if the build has none. */
        @JavascriptInterface
        public String streetsIndex() {
            try (InputStream in = getAssets().open("streets/index.json")) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            } catch (Exception e) { return ""; }
        }

        /** One packed road/water tile as base64, or "" if that area has none. */
        @JavascriptInterface
        public String streetsTile(int x, int y) {
            try (InputStream in = getAssets().open("streets/" + x + "_" + y + ".bin")) {
                return Base64.encodeToString(readAll(in), Base64.NO_WRAP);
            } catch (Exception e) { return ""; }
        }

        /** Description of the packed building map (for the 3D view), or "" if the build has none. */
        @JavascriptInterface
        public String buildingsIndex() {
            try (InputStream in = getAssets().open("buildings/index.json")) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            } catch (Exception e) { return ""; }
        }

        /** One packed building tile as base64, or "" if that area has none. */
        @JavascriptInterface
        public String buildingsTile(int x, int y) {
            try (InputStream in = getAssets().open("buildings/" + x + "_" + y + ".bin")) {
                return Base64.encodeToString(readAll(in), Base64.NO_WRAP);
            } catch (Exception e) { return ""; }
        }

        /**
         * Asks for full screen brightness while the launcher is on screen (other apps get the normal
         * setting back). Some FYT units drive the backlight from their MCU and ignore this.
         */
        @JavascriptInterface
        public void setBrightBoost(final boolean on) {
            main.post(() -> {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = on ? 1.0f : android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                getWindow().setAttributes(lp);
            });
        }

        /** Built-in defaults (Sleeper username, FantasyPros key added at build time). */
        @JavascriptInterface
        public String defaults() {
            try (InputStream in = getAssets().open("defaults.json")) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            } catch (Exception e) { return "{}"; }
        }

        /** Offline place search index (stores, restaurants, towns...) as JSON, or "" if the build has none. */
        @JavascriptInterface
        public String places() {
            try (InputStream in = getAssets().open("places/places.json")) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            } catch (Exception e) { return ""; }
        }

        /** One shard of the offline address index (see tools/fetch_addresses.py), or "" if missing. */
        @JavascriptInterface
        public String addrShard(String key) {
            if (key == null || !key.matches("[a-z0-9_]{2}")) return "";
            try (InputStream in = getAssets().open("addresses/" + key + ".json")) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            } catch (Exception e) { return ""; }
        }

        /** Turn-by-turn directions to a point in Google Maps; any other maps app if Google Maps is missing. */
        @JavascriptInterface
        public boolean navigateTo(double lat, double lon, String name) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + lat + "," + lon + "&mode=d"));
                i.setPackage("com.google.android.apps.maps");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (i.resolveActivity(getPackageManager()) != null) { startActivity(i); return true; }
            } catch (Exception ignored) { }
            try {
                String label = Uri.encode(name == null ? "" : name);
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon + "(" + label + ")"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) { return false; }
        }

        /** Search Google Maps (or any maps app) for text, near where the car is. */
        @JavascriptInterface
        public boolean searchInMaps(String query, double lat, double lon) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + lat + "," + lon + "?q=" + Uri.encode(query)));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) { return false; }
        }

        /**
         * Small HTTPS GET for services that don't allow calls from a web page (FantasyPros).
         * Runs in the background; the answer arrives as onHttp(id, status, body).
         */
        @JavascriptInterface
        public void httpGet(final String id, final String url, final String headerName, final String headerValue) {
            new Thread(() -> {
                int status = 0;
                String body = "";
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("Accept", "application/json");
                    if (headerName != null && !headerName.isEmpty()) c.setRequestProperty(headerName, headerValue);
                    status = c.getResponseCode();
                    InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
                    if (in != null) body = new String(readAll(in), StandardCharsets.UTF_8);
                    c.disconnect();
                } catch (Exception ignored) { }
                js("window.onHttp && onHttp(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(body) + ")");
            }).start();
        }



        /** Says a short phrase (turn directions) through the car speakers, ducking the music. */
        @JavascriptInterface
        public void speak(final String text) { main.post(() -> speakNow(text)); }

        /** True if this build includes the offline road graph for routing. */
        @JavascriptInterface
        public boolean routeReady() {
            try { String[] l = getAssets().list("route"); return l != null && l.length > 0; } catch (Exception e) { return false; }
        }

        /**
         * Finds a driving route on the offline map. Runs in the background; the answer arrives as
         * onRoute(id, {ok, dist, time, pts, steps}) or {ok:false, err}.
         */
        @JavascriptInterface
        public void route(final String id, final double lat, final double lon, final double dlat, final double dlon, final double heading, final boolean avoid) {
            new Thread(() -> {
                String out;
                try {
                    synchronized (routerLock) {
                        if (router == null) router = new Router(openGraph());
                        out = router.route(lat, lon, dlat, dlon, heading, avoid);
                    }
                } catch (Throwable e) {
                    out = "{\"ok\":false,\"err\":" + JSONObject.quote("Routing isn't available: " + e.getClass().getSimpleName()) + "}";
                }
                js("window.onRoute && onRoute(" + JSONObject.quote(id) + "," + out + ")");
            }).start();
        }

        @JavascriptInterface
        public String apps() { return appsJson == null ? "" : appsJson; }

        @JavascriptInterface
        public String media() { return mediaJson(); }

        /** Radio diagnostic (Settings, System). Returns the report and starts listening for radio broadcasts. */
        @JavascriptInterface
        public String radioDiag() {
            if (probe == null) probe = new RadioProbe(MainActivity.this);
            return probe.report();
        }

        @JavascriptInterface
        public String radioDeep() {
            if (probe == null) probe = new RadioProbe(MainActivity.this);
            return probe.deep();
        }

        /** Copies text to the clipboard (used by the diagnostic so it can be pasted into a message). */
        @JavascriptInterface
        public void copyText(String t) {
            main.post(() -> {
                try {
                    ((android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                            .setPrimaryClip(android.content.ClipData.newPlainText("Highlander", t));
                } catch (Exception ignored) { }
            });
        }

        /** True when the unit's radio service answered, so the launcher's own Radio screen can be used. */
        @JavascriptInterface
        public boolean radioReady() { return fyt != null && fyt.connected(); }

        @JavascriptInterface
        public String radioState() { return fyt == null ? "{}" : fyt.stateJson(); }

        /** Sends a radio command: code plus comma separated integer arguments (for example "7,65538"). */
        @JavascriptInterface
        public void radioCmd(int code, String csv) {
            if (fyt == null) return;
            try {
                String t = csv == null ? "0" : csv.trim();
                int[] a;
                if (t.equalsIgnoreCase("null")) a = null;
                else if (t.isEmpty() || t.equalsIgnoreCase("empty")) a = new int[0];
                else {
                    String[] p = t.split(",");
                    a = new int[p.length];
                    for (int i = 0; i < p.length; i++) a[i] = Integer.parseInt(p[i].trim());
                }
                fyt.cmd(code, a);
            } catch (Exception e) { note("RADIO bad command " + code + " " + csv); }
        }

        /** Makes the radio the active audio source (what the factory radio app does on open). */
        @JavascriptInterface
        public void radioClaim() { if (fyt != null) fyt.claim(); }

        @JavascriptInterface
        public void radioPresets() { if (fyt != null) fyt.refreshPresets(); }

        /** Copies the factory radio and system server install files (APKs) to the Download folder, so they can be
         *  shared and read to learn how the factory radio sends AM, power and save. Returns a short report. */
        @JavascriptInterface
        public String exportRadioApks() {
            StringBuilder out = new StringBuilder();
            String[] pkgs = {"com.syu.radio", "com.syu.carradio", "com.syu.ms"};
            for (String pkg : pkgs) {
                try {
                    android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                    java.util.List<String> files = new java.util.ArrayList<>();
                    files.add(ai.sourceDir);
                    if (ai.splitSourceDirs != null) java.util.Collections.addAll(files, ai.splitSourceDirs);
                    int n = 0;
                    for (String f : files) {
                        java.io.File src = new java.io.File(f);
                        String name = pkg + (n == 0 ? "" : "-split" + n) + ".apk";
                        n++;
                        out.append(name).append(": ").append(copyToDownloads(src, name)).append("\n");
                    }
                } catch (Exception e) { out.append(pkg).append(": not found (").append(e.getClass().getSimpleName()).append(")\n"); }
            }
            note("APK export\n" + out);
            return out.toString();
        }

        @JavascriptInterface
        public String radioLog() { return probe == null ? "" : probe.liveLog(); }

        @JavascriptInterface
        public void radioDiagStop() { if (probe != null) probe.stop(); }

        @JavascriptInterface
        public void mediaAction(String action) {
            main.post(() -> {
                // The factory radio has no media session. When it is on and nothing else is playing, the card
                // controls the radio itself: previous and next seek down and up.
                if (fyt != null && fyt.isOn() && (controller == null || controller.getPlaybackState() == null
                        || controller.getPlaybackState().getState() != PlaybackState.STATE_PLAYING)) {
                    if ("prev".equals(action)) { fyt.cmd(FytRadio.C_SEEK_DOWN); return; }
                    if ("next".equals(action)) { fyt.cmd(FytRadio.C_SEEK_UP); return; }
                }
                // Look at the sessions again right now: the list may have changed since the last callback
                // (the app can be restarted by the unit while we are away).
                if (hasMediaAccess()) {
                    try { pickController(mediaSessionManager.getActiveSessions(new ComponentName(MainActivity.this, MediaListener.class))); } catch (Exception ignored) { }
                }
                if (controller != null) {
                    final MediaController c = controller;
                    MediaController.TransportControls t = c.getTransportControls();
                    note("MEDIA button " + action + " -> " + c.getPackageName());
                    switch (action) {
                        case "prev": t.skipToPrevious(); break;
                        case "next": t.skipToNext(); break;
                        case "toggle":
                            PlaybackState st = c.getPlaybackState();
                            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
                            if (playing) t.pause();
                            else {
                                t.play();
                                // An app the unit has put to sleep can ignore the first play; if it is still not
                                // playing a moment later, ask again once.
                                main.postDelayed(() -> {
                                    PlaybackState now = c.getPlaybackState();
                                    if (c == controller && (now == null || now.getState() != PlaybackState.STATE_PLAYING)) {
                                        note("MEDIA play ignored, asking again");
                                        try { c.getTransportControls().play(); } catch (Exception ignored) { }
                                    }
                                }, 1500);
                            }
                            break;
                    }
                    return;
                }
                note("MEDIA button " + action + " -> no session, sending media key");
                // No now-playing access (or the app has no media session, like many FYT
                // radio apps): send the same media keys a steering-wheel button would.
                int code = "prev".equals(action) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        : "next".equals(action) ? KeyEvent.KEYCODE_MEDIA_NEXT
                        : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                try {
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                } catch (Exception ignored) { }
            });
        }

        /** Opens the screen where the user allows "now playing" access. */
        @JavascriptInterface
        public void requestMediaAccess() {
            // FYT builds sometimes hide the notification-access screen; fall back to this app's info page.
            String[] tries = {
                    "android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS",
                    "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS" };
            for (String action : tries) {
                try {
                    Intent i = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (action.contains("DETAIL")) {
                        i.putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                                new ComponentName(MainActivity.this, MediaListener.class).flattenToString());
                    }
                    if (i.resolveActivity(getPackageManager()) == null) continue;
                    startActivity(i);
                    return;
                } catch (Exception ignored) { }
            }
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }

        /** True once "Display over other apps" is on, which Android 10 requires to auto-open at startup. */
        @JavascriptInterface
        public boolean canAutoStart() {
            return Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public void requestAutoStart() {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) { }
            }
        }

        @JavascriptInterface
        public void openAndroidSettings() {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }
    }
}
