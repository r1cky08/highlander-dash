package com.highlanderdash.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Empty notification listener. Android only lets an app read "now playing"
 * info from other apps (radio, Spotify, Bluetooth audio) if the user has
 * granted it notification access, and that grant is tied to a service like
 * this one. The launcher never reads or stores notifications themselves.
 */
public class MediaListener extends NotificationListenerService {
}
