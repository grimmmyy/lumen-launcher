package io.github.grimmmyy.lumen;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Quick > Sleep timer: after the chosen time, pauses whatever is playing and
 * puts the TV to sleep. Sleep uses the Home-button accessibility service's
 * "lock screen" action (which sleeps a Fire TV); pausing uses the Now Playing
 * access. Kept per process: the Home-button service keeps the process alive.
 */
final class SleepTimer {
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static long at; // uptime when it fires; 0 = off
    private static Context app;
    private static final Runnable fire = () -> {
        at = 0;
        sleepNow(app);
    };

    private SleepTimer() {}

    static void set(Context ctx, int minutes) {
        app = ctx.getApplicationContext();
        handler.removeCallbacks(fire);
        at = minutes > 0 ? SystemClock.uptimeMillis() + minutes * 60_000L : 0;
        if (at > 0) handler.postAtTime(fire, at);
    }

    /** Minutes left, rounded up; 0 when off. */
    static int minutesLeft() {
        if (at == 0) return 0;
        return (int) Math.max(1, (at - SystemClock.uptimeMillis() + 59_999) / 60_000);
    }

    /** Pauses media, then sleeps. Returns false if the TV can't be put to sleep from here. */
    static boolean sleepNow(Context ctx) {
        if (ctx == null) return false;
        pauseAll(ctx);
        return HomeWatcherService.sleepDevice();
    }

    private static void pauseAll(Context ctx) {
        if (!NowPlaying.available(ctx)) return;
        try {
            MediaSessionManager m = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController c : m.getActiveSessions(new ComponentName(ctx, NowPlayingService.class))) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) c.getTransportControls().pause();
            }
        } catch (RuntimeException ignored) {
            // no access
        }
    }
}
