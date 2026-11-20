package io.github.grimmmyy.lumen;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * What's playing on the TV right now, from Android's media sessions (the same
 * source as a phone's lock-screen player). Lumen shows it as the Now Playing
 * card and controls it with the remote.
 *
 * Needs Lumen's notification listener to be enabled (see NowPlayingService);
 * without it, {@link #available} is false and nothing is shown. Watches only
 * while Lumen is on screen ({@link #start}/{@link #stop}).
 */
final class NowPlaying {
    interface Listener {
        void onNowPlayingChanged(State state); // main thread; state is null when nothing is playing
    }

    /** An immutable snapshot of the session to show. */
    static final class State {
        final String pkg;
        final String appLabel;
        final String title;
        final String subtitle;
        final Bitmap art;          // scaled down; may be null
        final boolean playing;
        final long position;       // ms, at {@link #updatedAt}
        final long duration;       // ms, 0 if unknown
        final long updatedAt;      // SystemClock.elapsedRealtime of position
        final float speed;
        final MediaController controller;

        State(MediaController c, String appLabel, String title, String subtitle, Bitmap art, PlaybackState ps, long duration) {
            this.controller = c;
            this.pkg = c.getPackageName();
            this.appLabel = appLabel;
            this.title = title;
            this.subtitle = subtitle;
            this.art = art;
            this.playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
            this.position = ps != null ? Math.max(0, ps.getPosition()) : 0;
            this.updatedAt = ps != null ? ps.getLastPositionUpdateTime() : SystemClock.elapsedRealtime();
            this.speed = ps != null ? ps.getPlaybackSpeed() : 0f;
            this.duration = duration;
        }

        /** Current position, advanced from the last update while playing. */
        long positionNow() {
            if (!playing || updatedAt <= 0) return position;
            long p = position + (long) ((SystemClock.elapsedRealtime() - updatedAt) * (speed == 0 ? 1 : speed));
            return duration > 0 ? Math.min(p, duration) : p;
        }

        /** Same thing on screen: avoids redrawing for no reason. */
        boolean sameAs(State o) {
            return o != null && pkg.equals(o.pkg) && TextUtils.equals(title, o.title)
                    && TextUtils.equals(subtitle, o.subtitle) && art == o.art && playing == o.playing
                    && duration == o.duration;
        }
    }

    /** Paused sessions stay on Home this long, so you can resume what you just stopped. */
    private static final long PAUSED_KEEP_MS = 30 * 60_000;
    private static final int ART_PX = 360;

    private final Context ctx;
    private final Executor io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ComponentName listener;
    private final List<MediaController> watched = new ArrayList<>();
    private MediaSessionManager sessions;
    private Listener callback;
    private State state;
    private final java.util.Map<String, Long> pausedSince = new java.util.HashMap<>();
    /** Which artwork {@link #art} is: metadata hands out a new Bitmap object on every read. */
    private String artKey;
    private Bitmap art;

    private final MediaSessionManager.OnActiveSessionsChangedListener onSessions = this::watch;
    private final MediaController.Callback onChange = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            update();
        }

        @Override
        public void onMetadataChanged(MediaMetadata m) {
            update();
        }

        @Override
        public void onSessionDestroyed() {
            update();
        }
    };

    NowPlaying(Context ctx, Executor io) {
        this.ctx = ctx.getApplicationContext();
        this.io = io;
        this.listener = new ComponentName(ctx, NowPlayingService.class);
    }

    /** True if Lumen may read media sessions (its notification listener is on). */
    static boolean available(Context ctx) {
        String enabled = android.provider.Settings.Secure.getString(ctx.getContentResolver(),
                "enabled_notification_listeners");
        if (enabled == null) return false;
        ComponentName me = new ComponentName(ctx, NowPlayingService.class);
        for (String s : enabled.split(":")) if (me.equals(ComponentName.unflattenFromString(s))) return true;
        return false;
    }

    State current() {
        return state;
    }

    void start(Listener l) {
        callback = l;
        if (!available(ctx)) {
            set(null);
            return;
        }
        sessions = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            sessions.addOnActiveSessionsChangedListener(onSessions, listener, main);
            watch(sessions.getActiveSessions(listener));
        } catch (SecurityException e) {
            sessions = null;
            set(null);
        }
    }

    void stop() {
        callback = null;
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(onSessions);
        sessions = null;
        for (MediaController c : watched) c.unregisterCallback(onChange);
        watched.clear();
    }

    private void watch(List<MediaController> controllers) {
        for (MediaController c : watched) c.unregisterCallback(onChange);
        watched.clear();
        if (controllers != null) {
            for (MediaController c : controllers) {
                c.registerCallback(onChange, main);
                watched.add(c);
            }
        }
        update();
    }

    /** Picks the session to show: anything playing, else the most recently paused one. */
    private void update() {
        MediaController best = null;
        long now = SystemClock.elapsedRealtime();
        for (MediaController c : watched) {
            PlaybackState ps = c.getPlaybackState();
            MediaMetadata md = c.getMetadata();
            if (md == null || TextUtils.isEmpty(title(md))) continue;
            int st = ps != null ? ps.getState() : PlaybackState.STATE_NONE;
            if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) {
                pausedSince.remove(c.getPackageName());
                best = c;
                break;
            }
            if (st == PlaybackState.STATE_PAUSED) {
                Long since = pausedSince.get(c.getPackageName());
                if (since == null) pausedSince.put(c.getPackageName(), since = now);
                if (now - since < PAUSED_KEEP_MS && best == null) best = c;
            }
        }
        if (best == null) {
            set(null);
            return;
        }
        MediaMetadata md = best.getMetadata();
        String title = title(md);
        String sub = joinNonEmpty(md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                md.getString(MediaMetadata.METADATA_KEY_ALBUM));
        if (TextUtils.isEmpty(sub)) sub = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        long duration = Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
        String label = appLabel(best.getPackageName());
        Bitmap source = artOf(md);
        final MediaController chosen = best;
        final String fTitle = title, fSub = sub;
        String key = source == null ? null
                : best.getPackageName() + "|" + title + "|" + fSub + "|" + source.getWidth() + "x" + source.getHeight();
        if (source != null && !key.equals(artKey)) {
            // Scale the artwork off the main thread; show the text right away.
            artKey = key;
            art = null;
            io.execute(() -> {
                Bitmap scaled = scale(source);
                main.post(() -> {
                    if (!key.equals(artKey)) return;
                    art = scaled;
                    if (scaled != null) scaled.prepareToDraw();
                    update();
                });
            });
        } else if (source == null) {
            artKey = null;
            art = null;
        }
        set(new State(chosen, label, fTitle, fSub, art, chosen.getPlaybackState(), duration));
    }

    private void set(State s) {
        State old = state;
        state = s;
        if (callback == null) return;
        if (s == null ? old != null : !s.sameAs(old)) callback.onNowPlayingChanged(s);
    }

    // ---- Controls -------------------------------------------------------------

    void playPause() {
        State s = state;
        if (s == null) return;
        if (s.playing) s.controller.getTransportControls().pause();
        else s.controller.getTransportControls().play();
    }

    void next() {
        if (state != null) state.controller.getTransportControls().skipToNext();
    }

    void previous() {
        if (state != null) state.controller.getTransportControls().skipToPrevious();
    }

    // ---- Helpers --------------------------------------------------------------

    private static String title(MediaMetadata md) {
        String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(t)) t = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        return t;
    }

    private static Bitmap artOf(MediaMetadata md) {
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b;
    }

    private static Bitmap scale(Bitmap src) {
        try {
            if (src.isRecycled()) return null;
            float k = Math.min(1f, ART_PX / (float) Math.max(src.getWidth(), src.getHeight()));
            return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * k)),
                    Math.max(1, Math.round(src.getHeight() * k)), true);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private final java.util.Map<String, String> labels = new java.util.HashMap<>();

    private String appLabel(String pkg) {
        String l = labels.get(pkg);
        if (l != null) return l;
        try {
            PackageManager pm = ctx.getPackageManager();
            l = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            l = pkg;
        }
        labels.put(pkg, l);
        return l;
    }

    private static String joinNonEmpty(String a, String b) {
        if (TextUtils.isEmpty(a)) return TextUtils.isEmpty(b) ? null : b;
        if (TextUtils.isEmpty(b)) return a;
        return a + "  ·  " + b;
    }
}
