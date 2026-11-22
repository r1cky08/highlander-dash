package com.highlanderdash.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Talks to the head unit's radio through the system server (com.syu.ms), the same way the factory radio
 * screen does. No root or special permission is needed: its toolkit service is exported.
 *
 * Codes (from the unit's own tables, read by the Radio diagnostic): module 1 is the radio; updates U_BAND 0,
 * U_FREQ 1, U_CHANNEL 3, U_RDS_TEXT 13, U_PS_TEXT 26, U_POWER 27; commands C_NEXT_CHANNEL 0, C_PREV_CHANNEL 1,
 * C_FREQ_UP 3, C_FREQ_DOWN 4, C_SEEK_UP 5, C_SEEK_DOWN 6. Frequencies: FM in units of 10 kHz (8750 to 10800),
 * AM in kHz (522 to 1620).
 *
 * This is a private system interface, so it is specific to FYT/Syu units and everything here fails quietly
 * when the unit does not have it.
 */
class FytRadio {
    interface Listener { void onChanged(); void onNote(String s); }

    private static final String TOOLKIT_DESC = "com.syu.ipc.IRemoteToolkit";
    private static final String MODULE_DESC = "com.syu.ipc.IRemoteModule";
    private static final String CB_DESC = "com.syu.ipc.IModuleCallback";
    private static final int MODULE_RADIO = 1;
    static final int C_NEXT_CHANNEL = 0, C_PREV_CHANNEL = 1, C_FREQ_UP = 3, C_FREQ_DOWN = 4, C_SEEK_UP = 5, C_SEEK_DOWN = 6;

    private final Context ctx;
    private final Listener listener;
    private IBinder toolkit, radio;
    private volatile IBinder mainMod;
    /** The unit's current audio app id (main module update 0): 1 is the radio, 0 none, others are other sources. */
    volatile int appId = -1;
    private boolean binding = false;
    private volatile long lastUpdate = 0;
    private int band = -1, freq = 0, channel = -1, power = -1;
    private String ps = "", rds = "";
    /** Saved station slots the unit lists (channel index -> frequency/text). FM indexes start at 65536, AM at 0. */
    private final TreeSet<Integer> channels = new TreeSet<>();
    private final Map<Integer, Integer> chanFreq = new HashMap<>();
    private final Map<Integer, String> chanText = new HashMap<>();
    private volatile boolean fetching = false;
    private volatile String lastCmd = "";

    FytRadio(Context c, Listener l) { ctx = c; listener = l; }

    private final Binder callback = new Binder() {
        { attachInterface(null, CB_DESC); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == 1) {
                try {
                    data.enforceInterface(CB_DESC);
                    int uc = data.readInt();
                    int[] ints = data.createIntArray();
                    float[] floats = data.createFloatArray();
                    String[] strs = data.createStringArray();
                    handle(uc, ints, floats, strs);
                } catch (Throwable e) { listener.onNote("RADIO callback error " + e); }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    /** Log-only listener on the main module, to see what the unit does when the radio stops (source changes, app switches). */
    private final Binder mainWatch = new Binder() {
        { attachInterface(null, CB_DESC); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == 1) {
                try {
                    data.enforceInterface(CB_DESC);
                    int uc = data.readInt();
                    int[] ints = data.createIntArray();
                    data.createFloatArray();
                    String[] strs = data.createStringArray();
                    listener.onNote("MAIN u" + uc + " ints=" + Arrays.toString(ints) + (strs != null ? " strs=" + Arrays.toString(strs) : ""));
                    if (uc == 0 && ints != null && ints.length > 0) { appId = ints[0]; listener.onChanged(); }
                } catch (Throwable e) { listener.onNote("MAIN callback error " + e); }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private void handle(int uc, int[] ints, float[] floats, String[] strs) {
        lastUpdate = System.currentTimeMillis();
        listener.onNote("RADIO u" + uc + " ints=" + Arrays.toString(ints) + (strs != null ? " strs=" + Arrays.toString(strs) : ""));
        int i0 = ints != null && ints.length > 0 ? ints[0] : 0;
        String s0 = strs != null && strs.length > 0 && strs[0] != null ? strs[0].trim() : "";
        switch (uc) {
            case 0: band = i0; break;
            case 1: freq = i0; break;
            case 3: channel = i0; break;
            case 13: rds = s0; break;
            case 26: ps = s0; break;
            case 27: power = i0; break;
            case 4: // channel frequency push: [index, freq] (layout assumed, logged above)
                if (ints != null && ints.length >= 2) { synchronized (channels) { channels.add(ints[0]); chanFreq.put(ints[0], ints[1]); } }
                break;
            case 14: // channel list entry: [index] with optional text
                if (ints != null && ints.length >= 1) { synchronized (channels) { channels.add(ints[0]); if (!s0.isEmpty()) chanText.put(ints[0], s0); } }
                break;
            default: return;
        }
        listener.onChanged();
    }

    /** Connects (or reconnects) and asks for pushed updates. Safe to call repeatedly. */
    synchronized void ensure() {
        if (binding) return;
        if (radio != null && radio.pingBinder()) return;
        radio = null;
        binding = true;
        new Thread(() -> {
            try {
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                final IBinder[] box = new IBinder[1];
                ServiceConnection sc = new ServiceConnection() {
                    @Override public void onServiceConnected(ComponentName n, IBinder b) { box[0] = b; latch.countDown(); }
                    @Override public void onServiceDisconnected(ComponentName n) { synchronized (FytRadio.this) { radio = null; } }
                };
                Intent i = new Intent("com.syu.ms.toolkit").setComponent(new ComponentName("com.syu.ms", "app.ToolkitService"));
                if (!ctx.bindService(i, sc, Context.BIND_AUTO_CREATE)) { listener.onNote("RADIO toolkit not available"); return; }
                latch.await(4, java.util.concurrent.TimeUnit.SECONDS);
                toolkit = box[0];
                if (toolkit == null) { listener.onNote("RADIO toolkit did not answer"); return; }
                Parcel d = Parcel.obtain(), r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(TOOLKIT_DESC);
                    d.writeInt(MODULE_RADIO);
                    toolkit.transact(1, d, r, 0);
                    r.readException();
                    radio = r.readStrongBinder();
                } finally { d.recycle(); r.recycle(); }
                if (radio == null) { listener.onNote("RADIO module missing"); return; }
                try { radio.linkToDeath(() -> { synchronized (FytRadio.this) { radio = null; } listener.onNote("RADIO service died"); }, 0); } catch (Exception ignored) { }
                for (int code : new int[]{0, 1, 3, 13, 26, 27}) register(radio, callback, code);
                // log-only extras to learn what changes when the radio stops (not used by the card)
                for (int code = 2; code <= 40; code++) if (code != 3 && code != 13 && code != 26 && code != 27) register(radio, callback, code);
                try {
                    Parcel d2 = Parcel.obtain(), r2 = Parcel.obtain();
                    IBinder main;
                    try {
                        d2.writeInterfaceToken(TOOLKIT_DESC);
                        d2.writeInt(0);
                        toolkit.transact(1, d2, r2, 0);
                        r2.readException();
                        main = r2.readStrongBinder();
                    } finally { d2.recycle(); r2.recycle(); }
                    mainMod = main;
                    if (main != null) {
                        for (int code = 0; code <= 60; code++) register(main, mainWatch, code);
                        listener.onNote("MAIN watching");
                    }
                } catch (Throwable e) { listener.onNote("MAIN watch failed " + e); }
                listener.onNote("RADIO connected");
                try { Thread.sleep(1200); } catch (InterruptedException ignored) { }
                refreshPresets();
            } catch (Throwable e) { listener.onNote("RADIO connect failed " + e); }
            finally { synchronized (FytRadio.this) { binding = false; } }
        }, "fyt-radio").start();
    }

    private void register(IBinder module, Binder cb, int code) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(MODULE_DESC);
            d.writeStrongBinder(cb);
            d.writeInt(code);
            d.writeInt(1);
            module.transact(3, d, r, 0);
        } catch (Throwable e) { if (code < 3) listener.onNote("RADIO register " + code + " failed " + e); }
        finally { d.recycle(); r.recycle(); }
    }

    /** Sends a radio command (seek, station, frequency step). */
    void cmd(int code) { cmd(code, new int[]{0}); }

    /** Sends a radio command with its integer arguments. */
    void cmd(final int code, final int[] args) {
        final IBinder rb = radio;
        lastCmd = code + (args == null ? "null" : Arrays.toString(args));
        if (rb == null) { ensure(); listener.onNote("RADIO command " + code + " not sent, not connected"); return; }
        new Thread(() -> {
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken(MODULE_DESC);
                d.writeInt(code);
                d.writeIntArray(args);
                d.writeFloatArray(null);
                d.writeStringArray(null);
                rb.transact(1, d, r, 0);
                listener.onNote("RADIO command " + code + (args == null ? " null" : Arrays.toString(args)) + " sent");
            } catch (Throwable e) { listener.onNote("RADIO command " + code + " failed " + e); }
            finally { d.recycle(); r.recycle(); }
        }, "fyt-radio-cmd").start();
    }

    /** Makes the radio the unit's active audio source, the way the factory radio app does
     *  (main module command 1 with app id 1, sent twice 300 ms apart like the factory code). */
    void claim() {
        final IBinder mb = mainMod;
        if (mb == null) { listener.onNote("RADIO claim skipped, main module not connected"); return; }
        new Thread(() -> {
            for (int k = 0; k < 2; k++) {
                Parcel d = Parcel.obtain(), r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(MODULE_DESC);
                    d.writeInt(1);
                    d.writeIntArray(new int[]{1});
                    d.writeFloatArray(null);
                    d.writeStringArray(null);
                    mb.transact(1, d, r, 0);
                } catch (Throwable e) { listener.onNote("RADIO claim failed " + e); }
                finally { d.recycle(); r.recycle(); }
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            }
            listener.onNote("RADIO claimed as audio source");
        }, "fyt-radio-claim").start();
    }

    /** Asks the radio module for a value (transaction get). Returns the int array it answers with, or null. */
    int[] get(int code, int[] args) {
        final IBinder rb = radio;
        if (rb == null) return null;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(MODULE_DESC);
            d.writeInt(code);
            d.writeIntArray(args);
            d.writeFloatArray(null);
            d.writeStringArray(null);
            rb.transact(2, d, r, 0);
            r.readException();
            int pos = r.dataPosition();
            int nn = r.readInt();
            int[] ints = null; String[] strs = null;
            if (nn != 0) {
                ints = r.createIntArray();
                r.createFloatArray();
                strs = r.createStringArray();
            }
            listener.onNote("RADIO get " + code + Arrays.toString(args) + " -> " + (ints == null ? "null" : Arrays.toString(ints)) + (strs != null ? " strs=" + Arrays.toString(strs) : "") + " (size " + r.dataSize() + ", pos0 " + pos + ")");
            return ints;
        } catch (Throwable e) {
            listener.onNote("RADIO get " + code + " failed " + e);
            return null;
        } finally { d.recycle(); r.recycle(); }
    }

    /** Looks up the frequency of each saved station slot the unit has listed. Runs once at a time. */
    void refreshPresets() {
        if (fetching || radio == null) return;
        fetching = true;
        new Thread(() -> {
            try {
                Integer[] list;
                synchronized (channels) { list = channels.toArray(new Integer[0]); }
                for (int idx : list) {
                    boolean have; synchronized (channels) { have = chanFreq.containsKey(idx); }
                    if (have) continue;
                    int[] v = get(4, new int[]{idx});
                    if (v != null) {
                        int f = 0;
                        for (int x : v) if (x >= 500 && x < 200000 && x != idx) f = x;
                        if (f > 0) synchronized (channels) { chanFreq.put(idx, f); }
                    }
                }
                listener.onChanged();
            } finally { fetching = false; }
        }, "fyt-radio-presets").start();
    }

    boolean connected() { return radio != null; }

    /** Everything the Radio screen shows, as JSON. */
    String stateJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", radio != null);
            o.put("on", isOn());
            o.put("freq", freq);
            boolean fm = band >= 65536 || freq >= 8000;
            o.put("fm", fm);
            o.put("band", band);
            o.put("chan", channel);
            o.put("name", ps);
            o.put("text", rds);
            o.put("last", lastCmd);
            o.put("app", appId);
            JSONArray arr = new JSONArray();
            synchronized (channels) {
                for (int idx : channels) {
                    JSONObject c = new JSONObject();
                    c.put("i", idx);
                    c.put("f", chanFreq.containsKey(idx) ? chanFreq.get(idx) : 0);
                    c.put("t", chanText.containsKey(idx) ? chanText.get(idx) : "");
                    arr.put(c);
                }
            }
            o.put("presets", arr);
        } catch (Exception ignored) { }
        return o.toString();
    }

    /** True when the radio reports it is on. */
    boolean isOn() {
        // Power (code 27) read 0 on the unit while the radio was playing, so it is not trusted as an on/off flag.
        // Only treat it as off if it has ever reported on and now reports off.
        if (power > 0) sawPowerOn = true;
        if (sawPowerOn && power <= 0) return false;
        return radio != null && freq > 0;
    }
    private boolean sawPowerOn = false;

    String freqText() {
        if (freq <= 0) return "";
        if (freq >= 8000) return String.format(Locale.US, "%.1f", freq / 100.0) + " FM";
        return freq + " AM";
    }

    /** The unit reports the band as the start of its index range: AM starts at 0, FM at 65536 (seen on the unit). */
    String bandText() {
        if (band < 0) return "";
        return band >= 65536 ? "FM" : "AM";
    }

    /** Saved station number the radio is on (1 and up), or empty. */
    String presetText() { return channel > 0 ? "P" + channel : ""; }

    String station() { return ps; }
    String text() { return rds; }
}
