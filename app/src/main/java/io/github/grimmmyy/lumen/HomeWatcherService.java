package io.github.grimmmyy.lumen;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * Fire OS won't let another app become the default home screen. This service
 * watches for the Amazon home screen to appear (Home button, boot, wake from
 * standby) and opens Lumen over it. Nothing on the system is changed: turn
 * off the service and the stock behavior is back.
 *
 * Pass-through screens: on Fire OS, opening Settings (and other Amazon
 * screens) briefly passes through the Amazon home activity on the way:
 * settings.v2 -> HomeActivity -> MainSettingsActivity, about 140 ms apart.
 * Redirecting on that would bounce you straight back to Lumen. A real Home
 * press shows the home activity on its own, so: when the home activity
 * appears less than a second after another app's window, wait briefly and
 * only redirect if nothing else opens. A plain Home press stays instant.
 *
 * Crash guard: if Lumen keeps crashing, or keeps getting bounced back to the
 * Amazon home screen, the service stops redirecting (safe mode) and leaves you
 * on the Amazon home screen. Safe mode ends once Lumen has been opened by hand
 * and has stayed up for a few seconds (see MainActivity).
 */
public class HomeWatcherService extends AccessibilityService {
    private static final String FIRE_LAUNCHER = "com.amazon.tv.launcher";
    private static final String TAG = "Lumen";

    /** 2 crashes within 5 minutes means Lumen is broken, not unlucky. */
    private static final int CRASH_LIMIT = 2;
    private static final long CRASH_WINDOW_MS = 5 * 60_000;
    /**
     * 6 redirects within 15 seconds, with Lumen never staying up long enough to
     * reset the count, means we're ping-ponging, crash or not.
     */
    private static final int REDIRECT_LIMIT = 6;
    private static final long REDIRECT_WINDOW_MS = 15_000;

    /** The home activity this soon after another app's window may be a pass-through. */
    private static final long PASS_THROUGH_WINDOW_MS = 1_000;
    /** How long to wait for the real destination of a pass-through. */
    private static final long PASS_THROUGH_WAIT_MS = 600;

    private Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable deferredRedirect = this::redirect;
    private boolean redirectPending;
    private String lastPkg;
    private long lastAt;

    @Override
    protected void onServiceConnected() {
        prefs = new Prefs(this);
        Log.i(TAG, "watcher connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefs == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkgCs = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkgCs == null || cls == null) return;
        String pkg = pkgCs.toString();
        // System UI (volume, notifications) overlays apps; it never means a screen changed.
        if (pkg.equals("com.android.systemui")) return;
        long now = SystemClock.uptimeMillis();

        // Only the home screen itself. Other launcher screens (Settings) are left alone.
        boolean home = FIRE_LAUNCHER.equals(pkg) && cls.toString().contains("HomeActivity");
        if (!home) {
            if (redirectPending) {
                // The home activity was a pass-through: something else opened after it.
                cancelPending();
                Log.i(TAG, "Fire home was a pass-through to " + pkg + "/" + cls + ", not redirecting");
            }
            lastPkg = pkg;
            lastAt = now;
            return;
        }

        boolean passThrough = lastPkg != null && !lastPkg.equals(FIRE_LAUNCHER)
                && !lastPkg.equals(getPackageName()) && now - lastAt < PASS_THROUGH_WINDOW_MS;
        lastPkg = pkg;
        lastAt = now;
        cancelPending();
        if (passThrough) {
            redirectPending = true;
            handler.postDelayed(deferredRedirect, PASS_THROUGH_WAIT_MS);
        } else {
            redirect();
        }
    }

    private void cancelPending() {
        redirectPending = false;
        handler.removeCallbacks(deferredRedirect);
    }

    private void redirect() {
        redirectPending = false;
        int crashes = prefs.crashesWithin(CRASH_WINDOW_MS);
        int redirects = prefs.redirectsWithin(REDIRECT_WINDOW_MS);
        Log.i(TAG, "Fire home shown: bypass=" + prefs.isFireHomeBypassed() + " safe=" + prefs.isSafeMode()
                + " crashes=" + crashes + " redirects=" + redirects);
        if (prefs.isFireHomeBypassed() || prefs.isSafeMode()) return;

        if (crashes >= CRASH_LIMIT || redirects >= REDIRECT_LIMIT) {
            prefs.setSafeMode(true);
            Toast.makeText(this, "Lumen had a problem, so the Home button opens Fire TV home for now. "
                    + "Open Lumen from Your Apps to turn it back on.", Toast.LENGTH_LONG).show();
            return;
        }

        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
        // Count the redirect after asking for Lumen, and off this thread (Lumen's
        // screen is created on it). It is a synchronous disk write so the count
        // survives a crash; done first, it delayed opening Lumen (43 ms in one
        // measurement on a Fire TV Stick 4K Max).
        LumenApp.io().execute(prefs::recordRedirect);
    }

    @Override
    public void onDestroy() {
        cancelPending();
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {}
}
