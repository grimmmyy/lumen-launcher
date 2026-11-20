package io.github.grimmmyy.lumen;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Android lets Lumen see which app is playing media: reading
 * media sessions (MediaSessionManager.getActiveSessions) requires an enabled
 * notification listener. Lumen never reads, stores or shows notifications.
 * Turned on with: adb shell cmd notification allow_listener
 * io.github.grimmmyy.lumen/io.github.grimmmyy.lumen.NowPlayingService
 */
public class NowPlayingService extends NotificationListenerService {
}
