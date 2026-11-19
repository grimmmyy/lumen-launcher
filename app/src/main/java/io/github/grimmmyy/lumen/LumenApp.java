package io.github.grimmmyy.lumen;

import android.app.Application;

/** Records every crash so the Home-button watcher can tell when Lumen is stuck crashing. */
public class LumenApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                new Prefs(this).recordCrash();
            } catch (Throwable ignored) {
                // never let the crash recorder hide the real crash
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
}
