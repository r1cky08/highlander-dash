package com.highlanderdash.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.XmlResourceParser;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.provider.Settings;

import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Diagnostic for the factory radio. The now-playing card works for apps that publish an Android
 * media session (Spotify does) but not for the head unit's radio. This reports what the unit
 * actually exposes: media sessions, the radio-related apps and their services, and the broadcasts
 * those apps declare, then listens for those broadcasts while the radio plays and logs what arrives.
 * It only reads and logs; it changes nothing.
 */
class RadioProbe {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private final Context ctx;
    private final List<String> log = new ArrayList<>();
    private BroadcastReceiver receiver;
    private int actionCount = 0;

    RadioProbe(Context c) { ctx = c; }

    /** Static report: sessions, radio-like packages, services, providers, declared actions. */
    String report() {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = ctx.getPackageManager();

        sb.append("MEDIA SESSIONS (apps Android lists as playing media)\n");
        try {
            String enabled = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            if (enabled == null || !enabled.contains(ctx.getPackageName())) {
                sb.append("  Song info access is OFF, so sessions cannot be listed.\n");
            } else {
                MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
                List<MediaController> l = msm.getActiveSessions(new ComponentName(ctx, MediaListener.class));
                if (l.isEmpty()) sb.append("  none right now\n");
                for (MediaController c : l) {
                    PlaybackState st = c.getPlaybackState();
                    sb.append("  ").append(c.getPackageName()).append("  state=")
                            .append(st == null ? "none" : String.valueOf(st.getState()))
                            .append("  title=").append(c.getMetadata() == null ? "none" : c.getMetadata().getString("android.media.metadata.TITLE"))
                            .append("\n");
                }
            }
        } catch (Exception e) { sb.append("  error: ").append(e.getClass().getSimpleName()).append("\n"); }

        sb.append("\nRADIO-RELATED APPS\n");
        Set<String> actions = new LinkedHashSet<>();
        try {
            List<PackageInfo> all = pm.getInstalledPackages(PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS);
            for (PackageInfo p : all) {
                String n = p.packageName.toLowerCase(Locale.US);
                boolean hit = n.contains("radio") || n.contains("tuner") || n.contains("syu") || n.contains("fyt")
                        || n.contains("autochips") || n.contains("carmedia") || n.contains("music");
                if (!hit) continue;
                String label = "";
                try { label = String.valueOf(pm.getApplicationLabel(p.applicationInfo)); } catch (Exception ignored) { }
                boolean sys = (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                sb.append("* ").append(p.packageName).append("  (").append(label).append(sys ? ", system" : "").append(")\n");
                if (p.services != null) for (ServiceInfo s : p.services) {
                    sb.append("    service ").append(shortName(s.name, p.packageName)).append(s.exported ? "  [exported]" : "")
                            .append(s.permission != null ? "  perm=" + s.permission : "").append("\n");
                }
                if (p.providers != null) for (ProviderInfo pr : p.providers) {
                    sb.append("    provider ").append(pr.authority).append(pr.exported ? "  [exported]" : "").append("\n");
                }
                int rc = p.receivers == null ? 0 : p.receivers.length;
                sb.append("    receivers: ").append(rc).append("\n");
                List<String> a = manifestActions(pm, p.packageName);
                if (!a.isEmpty()) {
                    sb.append("    declared actions:\n");
                    for (String x : a) { sb.append("      ").append(x).append("\n"); actions.add(x); }
                }
            }
        } catch (Exception e) { sb.append("  error: ").append(e.getClass().getSimpleName()).append("\n"); }
        sb.append("\nListening for ").append(actions.size() + COMMON.length).append(" broadcast types. Play the radio and change station, band and volume. What arrives shows below.\n");
        startListening(actions);
        return sb.toString();
    }

    private static final String[] COMMON = {
            "com.android.music.metachanged", "com.android.music.playstatechanged", "com.android.music.playbackcomplete",
            "com.android.music.queuechanged", "android.media.AUDIO_BECOMING_NOISY", "android.media.VOLUME_CHANGED_ACTION",
            "com.android.music.musicservicecommand", "com.syu.ms.ACC_ON", "com.syu.ms.ACC_OFF" };

    private static String shortName(String n, String pkg) { return n.startsWith(pkg) ? n.substring(pkg.length()) : n; }

    /** Reads the intent actions an app lists in its manifest (receivers, services, activities). */
    private List<String> manifestActions(PackageManager pm, String pkg) {
        List<String> out = new ArrayList<>();
        XmlResourceParser x = null;
        try {
            x = pm.getResourcesForApplication(pkg).getAssets().openXmlResourceParser("AndroidManifest.xml");
            String comp = "";
            int ev;
            while ((ev = x.next()) != XmlResourceParser.END_DOCUMENT) {
                if (ev != XmlResourceParser.START_TAG) continue;
                String t = x.getName();
                if (t.equals("receiver") || t.equals("service") || t.equals("activity") || t.equals("provider")) {
                    String nm = x.getAttributeValue(ANDROID, "name");
                    comp = t + " " + (nm == null ? "" : shortName(nm, pkg));
                } else if (t.equals("action")) {
                    String a = x.getAttributeValue(ANDROID, "name");
                    if (a != null && !a.startsWith("android.intent.action.MAIN") && out.size() < 60) out.add(a + "   <- " + comp);
                }
            }
        } catch (Exception ignored) {
        } finally { if (x != null) x.close(); }
        return out;
    }

    private void startListening(Set<String> declared) {
        stop();
        IntentFilter f = new IntentFilter();
        int n = 0;
        for (String d : declared) {
            String a = d.contains("   <- ") ? d.substring(0, d.indexOf("   <- ")) : d;
            if (a.startsWith("android.intent.action.") || a.startsWith("android.net.") || a.startsWith("android.bluetooth.")) continue;
            f.addAction(a); n++;
        }
        for (String a : COMMON) f.addAction(a);
        actionCount = n + COMMON.length;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                StringBuilder sb = new StringBuilder();
                sb.append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())).append("  ").append(i.getAction());
                Bundle b = i.getExtras();
                if (b != null) for (String k : b.keySet()) {
                    String v;
                    try { v = String.valueOf(b.get(k)); } catch (Exception e) { v = "?"; }
                    if (v.length() > 60) v = v.substring(0, 60) + "...";
                    sb.append("\n      ").append(k).append(" = ").append(v);
                }
                synchronized (log) { log.add(sb.toString()); while (log.size() > 120) log.remove(0); }
            }
        };
        try { ctx.registerReceiver(receiver, f); } catch (Exception e) { receiver = null; }
    }


    /**
     * Step 2. The radio app has no broadcasts, so look at how the unit's system server (com.syu.ms) exposes
     * the radio: readable content providers, whether its services can be bound and what interface they
     * speak, and the names of that interface's methods and constants (read from the system app's own code).
     * Read-only: it never sends a command.
     */
    String deep() {
        StringBuilder sb = new StringBuilder();
        // 1. Content providers
        sb.append("PROVIDERS (readable data)\n");
        String[] uris = { "content://com.syu.radio", "content://com.syu.ms.provider", "content://com.syu.settingsProvider", "content://com.syu.protocolProvider" };
        for (String u : uris) {
            sb.append("* ").append(u).append("\n");
            try (Cursor c = ctx.getContentResolver().query(Uri.parse(u), null, null, null, null)) {
                if (c == null) { sb.append("    no cursor\n"); continue; }
                sb.append("    columns: ");
                for (int i = 0; i < c.getColumnCount(); i++) sb.append(c.getColumnName(i)).append(i + 1 < c.getColumnCount() ? ", " : "");
                sb.append("\n    rows: ").append(c.getCount()).append("\n");
                int r = 0;
                while (c.moveToNext() && r++ < 8) {
                    sb.append("      ");
                    for (int i = 0; i < c.getColumnCount(); i++) { String v; try { v = c.getString(i); } catch (Exception e) { v = "?"; } if (v != null && v.length() > 40) v = v.substring(0, 40) + "..."; sb.append(v).append(" | "); }
                    sb.append("\n");
                }
            } catch (Exception e) { sb.append("    ").append(e.getClass().getSimpleName()).append(": ").append(String.valueOf(e.getMessage()).replace('\n', ' ')).append("\n"); }
        }
        // 2. Can we bind to the system server?
        sb.append("\nSERVICES (can the launcher connect, and what does it speak)\n");
        String[][] svc = { { "com.syu.ms", "app.ToolkitService", "com.syu.ms.toolkit" }, { "com.syu.ms", "app.ModuleService", "com.syu.ms.radio" },
                { "com.syu.radio", ".broadcast.MyService", "com.syu.radio.keepAlive" } };
        for (String[] t : svc) {
            String cls = t[1].startsWith(".") ? t[0] + t[1] : t[1];
            sb.append("* ").append(t[0]).append("/").append(cls).append("  action=").append(t[2]).append("\n");
            final CountDownLatch latch = new CountDownLatch(1);
            final String[] res = { "no answer in 3 s" };
            ServiceConnection sc = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName n, IBinder b) {
                    try { res[0] = "connected, interface=" + b.getInterfaceDescriptor() + ", alive=" + b.pingBinder(); } catch (Exception e) { res[0] = "connected, " + e; }
                    latch.countDown();
                }
                @Override public void onServiceDisconnected(ComponentName n) { }
            };
            try {
                Intent i = new Intent(t[2]).setComponent(new ComponentName(t[0], cls));
                boolean ok = ctx.bindService(i, sc, Context.BIND_AUTO_CREATE);
                if (!ok) res[0] = "bindService refused";
                else latch.await(3, TimeUnit.SECONDS);
            } catch (Exception e) { res[0] = e.getClass().getSimpleName() + ": " + e.getMessage(); }
            try { ctx.unbindService(sc); } catch (Exception ignored) { }
            sb.append("    ").append(res[0]).append("\n");
        }
        // 3. The interface classes. The IPC classes are not inside the apps' own code, so ask for them by name,
        //    and read the radio's constant tables from the radio app itself.
        sb.append("\nINTERFACE CLASSES\n");
        String[] want = { "com.syu.ipc.IRemoteToolkit", "com.syu.ipc.IRemoteToolkit$Stub", "com.syu.ipc.IRemoteModule", "com.syu.ipc.IRemoteModule$Stub",
                "com.syu.ipc.IModuleCallback", "com.syu.ipc.IModuleCallback$Stub", "com.syu.ipc.ModuleCallback", "com.syu.ipc.ModuleObject",
                "com.syu.ipc.RemoteModuleProxy", "com.syu.ipc.RemoteToolkitProxy", "com.syu.ipc.FinalToolkit" };
        ClassLoader[] loaders = new ClassLoader[2];
        try { loaders[0] = ctx.createPackageContext("com.syu.radio", Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY).getClassLoader(); } catch (Throwable e) { sb.append("  radio app code: ").append(e.getClass().getSimpleName()).append("\n"); }
        try { loaders[1] = ctx.createPackageContext("com.syu.ms", Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY).getClassLoader(); } catch (Throwable e) { sb.append("  system server code: ").append(e.getClass().getSimpleName()).append("\n"); }
        for (String n : want) {
            Class<?> k = null;
            for (ClassLoader cl : loaders) { if (cl == null || k != null) continue; try { k = Class.forName(n, false, cl); } catch (Throwable ignored) { } }
            if (k == null) { try { k = Class.forName(n); } catch (Throwable ignored) { } }
            if (k == null) { sb.append("* ").append(n).append("  (not found)\n"); continue; }
            sb.append("* ").append(n).append("\n");
            try {
                for (Method m : k.getDeclaredMethods()) {
                    if (m.isSynthetic()) continue;
                    StringBuilder a2 = new StringBuilder();
                    for (Class<?> p : m.getParameterTypes()) a2.append(p.getName().replace("java.lang.", "")).append(", ");
                    sb.append("    ").append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(').append(a2).append(")\n");
                }
                for (Field f : k.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                    f.setAccessible(true);
                    sb.append("    ").append(f.getName()).append(" = ").append(f.getInt(null)).append("\n");
                }
            } catch (Throwable e) { sb.append("    (cannot inspect: ").append(e.getClass().getSimpleName()).append(")\n"); }
        }
        // The radio app's constant tables (update codes the radio screen listens to, and the commands it sends)
        sb.append("\nRADIO APP CONSTANTS (com.syu.radio)\n");
        try {
            Context rctx = ctx.createPackageContext("com.syu.radio", Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader cl = rctx.getClassLoader();
            List<String> names = new ArrayList<>();
            try {
                dalvik.system.DexFile df = new dalvik.system.DexFile(rctx.getPackageCodePath());
                java.util.Enumeration<String> en = df.entries();
                while (en.hasMoreElements()) names.add(en.nextElement());
                sb.append("  ").append(names.size()).append(" classes\n");
            } catch (Throwable e) { sb.append("  could not list classes: ").append(e).append("\n"); }
            java.util.Collections.sort(names);
            int shown = 0;
            for (String n : names) {
                String l = n.toLowerCase(Locale.US);
                if (!(l.contains("final") || l.contains("moduleobject") || l.contains("remote") || l.contains("callback")) || n.contains("$")) continue;
                if (shown++ > 40 || sb.length() > 60000) { sb.append("  ...more\n"); break; }
                sb.append("* ").append(n).append("\n");
                try {
                    Class<?> k = Class.forName(n, false, cl);
                    int cnt = 0;
                    for (Field f : k.getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                        if (cnt++ > 150) break;
                        f.setAccessible(true);
                        sb.append("    ").append(f.getName()).append(" = ").append(f.getInt(null)).append("\n");
                    }
                    if (cnt == 0) for (Method m : k.getDeclaredMethods()) { if (!m.isSynthetic() && cnt++ < 25) sb.append("    ").append(m.getName()).append("()\n"); }
                } catch (Throwable e) { sb.append("    (cannot inspect: ").append(e.getClass().getSimpleName()).append(")\n"); }
            }
        } catch (Throwable e) { sb.append("  ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n"); }
        return sb.toString();
    }

    void note(String t) {
        synchronized (log) { log.add(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + t); while (log.size() > 120) log.remove(0); }
    }

    String liveLog() {
        synchronized (log) {
            if (log.isEmpty()) return "(nothing heard yet)";
            StringBuilder sb = new StringBuilder();
            for (int i = log.size() - 1; i >= 0; i--) sb.append(log.get(i)).append("\n");
            return sb.toString();
        }
    }

    void stop() {
        if (receiver != null) { try { ctx.unregisterReceiver(receiver); } catch (Exception ignored) { } receiver = null; }
    }
}
