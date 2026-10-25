package io.freemytv;

import android.service.notification.NotificationListenerService;

/**
 * Only there so the home screen may read the TV's media sessions (what Spotify is playing, to show it and pause or skip
 * it): Android lets an enabled notification listener's app list them. It reads no notifications. Allowed once with
 * `adb shell cmd notification allow_listener io.freemytv/io.freemytv.MediaListener`.
 */
public final class MediaListener extends NotificationListenerService {
}
