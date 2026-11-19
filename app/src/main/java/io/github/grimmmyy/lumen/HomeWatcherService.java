package io.github.grimmmyy.lumen;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS won't let another app become the default home screen. This service
 * watches for the Amazon home screen to appear (Home button, boot, wake from
 * standby) and opens Lumen over it. Nothing on the system is changed: turn
 * off the service and the stock behavior is back.
 */
public class HomeWatcherService extends AccessibilityService {
    private static final String FIRE_LAUNCHER = "com.amazon.tv.launcher";

    private Prefs prefs;

    @Override
    protected void onServiceConnected() {
        prefs = new Prefs(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null || !FIRE_LAUNCHER.contentEquals(pkg)) return;
        // Only the home screen itself. Other launcher screens are left alone.
        if (!cls.toString().contains("HomeActivity")) return;
        if (prefs != null && prefs.isFireHomeBypassed()) return;

        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
    }

    @Override
    public void onInterrupt() {}
}
