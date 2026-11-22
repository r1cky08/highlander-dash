package com.highlanderdash.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * FYT head units don't fully power off; with the ignition off they sleep and
 * on ACC-on they bring their own stock launcher (or the last app) back up.
 * This catches both a real boot and FYT's ACC-on wake and puts the Highlander
 * Launcher back on screen. It fires twice: once right away and once a few
 * seconds later, after the stock launcher has finished grabbing the screen.
 *
 * Android 10 only lets a background app open a screen if it has
 * "Display over other apps", which the launcher asks for on first run.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> bringHome(app));
        h.postDelayed(() -> bringHome(app), 3500);
        h.postDelayed(() -> { bringHome(app); pending.finish(); }, 7000);
    }

    static void bringHome(Context c) {
        try {
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            c.startActivity(i);
        } catch (Exception ignored) { }
    }
}
