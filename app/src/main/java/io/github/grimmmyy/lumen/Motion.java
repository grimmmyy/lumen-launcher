package io.github.grimmmyy.lumen;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

/**
 * Shared motion settings. When a D-pad key is held down (auto-repeat, about
 * 20 presses a second), animations get shorter so scrolling keeps up with
 * focus instead of queueing behind it.
 */
final class Motion {
    static final Interpolator SCROLL = new DecelerateInterpolator(1.8f);
    static final int SCROLL_MS = 240;
    static final int SCROLL_FAST_MS = 110;

    private static long lastRepeatAt;
    /** Tilt, light sweep and launch zoom (Layout > Motion effects). */
    static boolean effects = true;

    private Motion() {}

    static void onKey(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() > 0) {
            lastRepeatAt = SystemClock.uptimeMillis();
        }
    }

    /** True while a direction key is being held. */
    static boolean fast() {
        return SystemClock.uptimeMillis() - lastRepeatAt < 150;
    }

    static int scrollDuration() {
        return fast() ? SCROLL_FAST_MS : SCROLL_MS;
    }
}
