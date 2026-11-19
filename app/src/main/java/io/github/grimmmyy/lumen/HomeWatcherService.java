package io.github.grimmmyy.lumen;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * Fire OS won't let another app become the default home screen. This service
 * watches for the Amazon home screen to appear (Home button, boot, wake from
 * standby) and opens Lumen over it. Nothing on the system is changed: turn
 * off the service and the stock behavior is back.
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

    private Prefs prefs;

    @Override
    protected void onServiceConnected() {
        prefs = new Prefs(this);
        Log.i(TAG, "watcher connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefs == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null || !FIRE_LAUNCHER.contentEquals(pkg)) return;
        // Only the home screen itself. Other launcher screens are left alone.
        if (!cls.toString().contains("HomeActivity")) return;
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

        prefs.recordRedirect();
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
    }

    @Override
    public void onInterrupt() {}
}
