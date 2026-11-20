package io.github.grimmmyy.lumen;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Records every crash so the Home-button watcher can tell when Lumen is stuck
 * crashing, and holds what should outlive one home screen: the worker threads
 * and the tile art in memory. Fire TV owners often turn on "Don't keep
 * activities", which rebuilds Lumen's screen on every Home press; with these
 * kept per process, a rebuilt screen gets its art from memory, not from disk.
 */
public class LumenApp extends Application {
    /** Low-priority workers, so background loading never competes with drawing frames. */
    private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> new Thread(() -> {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        r.run();
    }, "lumen-io"));
    private static ArtCache art;
    private static float artRadius;

    static ExecutorService io() {
        return IO;
    }

    /** The shared art cache; a new one only if the tile corner radius changed (Larger tiles). */
    static ArtCache art(Context ctx, float radiusPx) {
        if (art == null || artRadius != radiusPx) {
            art = new ArtCache(ctx, IO, new Handler(Looper.getMainLooper()), radiusPx);
            artRadius = radiusPx;
        }
        return art;
    }

    /** Set when the screen turns on; Home asks "Who's watching?" once (see MainActivity). */
    static volatile boolean screenJustOn;

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, android.content.Intent i) {
                screenJustOn = true;
            }
        }, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON));
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
