package io.github.grimmmyy.lumen;

import android.content.Intent;
import android.graphics.drawable.Drawable;

/** One launchable app on the device. */
final class AppEntry {
    final String pkg;
    final String label;
    final Drawable banner; // TV banner (16:9), may be null
    final Drawable icon;
    final int color;       // dominant color, used for tile and backdrop tints
    final Intent launch;

    AppEntry(String pkg, String label, Drawable banner, Drawable icon, int color, Intent launch) {
        this.pkg = pkg;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.color = color;
        this.launch = launch;
    }
}
