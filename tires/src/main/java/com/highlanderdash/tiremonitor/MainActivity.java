package com.highlanderdash.tiremonitor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Tire Monitor screen. The listening itself (both sensor sets, timing, log) lives in TireService.
 * LeePee: service 0xFBB0, manufacturer data under company id 0x0100 (16 bytes): 0-5 sensor id, 6-9 pressure in Pa above
 * atmosphere (little-endian), 10-13 temperature in 0.01 C, 14 battery %, 15 alert flag. The second set is decoded in TireDecoder.
 */
public class MainActivity extends Activity {

    private WebView web;
    private boolean asked = false, pageReady = false;
    private SharedPreferences prefs;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF08080A);
        getWindow().setNavigationBarColor(0xFF08080A);
        prefs = getSharedPreferences("tires", MODE_PRIVATE);
        web = new WebView(this);
        web.setBackgroundColor(0xFF08080A);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Phone");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { pageReady = true; replay(); }
        });
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    @Override
    protected void onResume() {
        super.onResume();
        TireService.listener = code -> runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
        ensureService();
        if (pageReady) replay();
    }

    @Override
    protected void onPause() {
        super.onPause();
        TireService.listener = null;
        // With background scanning off, listening stops when the screen closes.
        if (!prefs.getBoolean("bg", true)) stopService(new Intent(this, TireService.class));
    }

    @Override
    public void onBackPressed() {
        // The Compare screen closes first; otherwise Back leaves the app as usual.
        web.evaluateJavascript("window.onBack ? onBack() : false", v -> { if (!"true".equals(v)) MainActivity.super.onBackPressed(); });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) { ensureService(); }

    /** Sends the page the newest reading of every sensor heard while it was closed. */
    private void replay() {
        for (String code : TireService.replay()) web.evaluateJavascript(code, null);
    }

    private List<String> missing() {
        List<String> m = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            m.add(Manifest.permission.ACCESS_FINE_LOCATION);
            m.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            m.add(Manifest.permission.BLUETOOTH_SCAN);
        return m;
    }

    /** Starts the listening service once the permissions are in; asks for them the first time. */
    private void ensureService() {
        List<String> m = missing();
        if (!m.isEmpty()) {
            TireService.status = "noperm";
            if (!asked) {
                asked = true;
                // Android 13 and up also ask before showing the "listening" notification; the app works without it.
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    m.add(Manifest.permission.POST_NOTIFICATIONS);
                requestPermissions(m.toArray(new String[0]), 1);
            }
            return;
        }
        try { startForegroundService(new Intent(this, TireService.class)); } catch (Exception e) { TireService.status = "failed"; }
    }

    private class Bridge {
        /** "ok" while scanning; otherwise why not (noperm, nobt, off, locoff, failed). */
        @JavascriptInterface public String status() { return TireService.status; }

        /** "broadcasts heard,tire packets" since the app opened. */
        @JavascriptInterface public String stats() { return TireService.heard + "," + TireService.packets; }

        @JavascriptInterface public void rescan() { runOnUiThread(() -> { asked = false; ensureService(); }); }

        /** Opens this app's page in Android settings, for granting permissions after a "Don't allow". */
        @JavascriptInterface
        public void appSettings() {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { }
        }

        /** Whether listening carries on with the screen off (a notification shows while it does). */
        @JavascriptInterface public boolean bg() { return prefs.getBoolean("bg", true); }

        @JavascriptInterface public void setBg(boolean on) { prefs.edit().putBoolean("bg", on).apply(); }

        /** Per-sensor timing since the window started, as JSON (see TireStats). */
        @JavascriptInterface public String tireTiming() { return TireService.stats.json(SystemClock.elapsedRealtime()); }

        /** Starts a new timing window from now (a new "leg"); the finished one is written to the log first. */
        @JavascriptInterface public void tireTimingReset() { TireService.appendLog(TireService.stats.reset(SystemClock.elapsedRealtime())); }

        /** The last few minutes per sensor for the strip chart, as JSON. */
        @JavascriptInterface public String tireTimeline(int minutes) { return TireService.stats.timeline(SystemClock.elapsedRealtime(), minutes); }

        /** Which wheel each sensor is on, as {"AA:BB:..":"FL"}; stamped on the log rows. */
        @JavascriptInterface
        public void tireSetWheels(String json) {
            try {
                JSONObject o = new JSONObject(json);
                HashMap<String, String> m = new HashMap<>();
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) { String k = it.next(); m.put(k, o.optString(k)); }
                TireService.stats.setWheels(m);
            } catch (Exception ignored) { }
        }

        /** The newest rows of the per-minute log (CSV with a header line), for pasting into a message. */
        @JavascriptInterface public String tireLog(int maxRows) { return TireService.readLog(getFilesDir(), maxRows); }

        /** Every raw sensor packet heard recently (newest 1500), as CSV text, for working out what the bytes mean. */
        @JavascriptInterface public String rawLog() { return TireService.rawText(); }

        @JavascriptInterface public void rawLogClear() { TireService.rawClear(); }

        /** Deletes the saved log. */
        @JavascriptInterface public void tireLogClear() { TireService.clearLog(getFilesDir()); }

        @JavascriptInterface
        public void copyText(String t) {
            runOnUiThread(() -> {
                try {
                    ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Tire Monitor", t));
                } catch (Exception ignored) { }
            });
        }
    }
}
