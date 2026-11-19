package io.github.grimmmyy.lumen;

import android.content.ComponentName;
import android.content.Intent;

/**
 * One launchable app. Deliberately holds no drawables: artwork is rendered
 * once into a small bitmap by {@link ArtCache} and kept on disk, so this list
 * is cheap to keep in memory and to snapshot.
 */
final class AppEntry {
    final String pkg;
    final String label;
    final String activity;
    final String category;
    final long updated; // lastUpdateTime, part of the artwork cache key

    AppEntry(String pkg, String label, String activity, String category, long updated) {
        this.pkg = pkg;
        this.label = label;
        this.activity = activity;
        this.category = category;
        this.updated = updated;
    }

    ComponentName component() {
        return new ComponentName(pkg, activity);
    }

    Intent launchIntent() {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(category)
                .setComponent(component())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    boolean sameAs(AppEntry o) {
        return pkg.equals(o.pkg) && label.equals(o.label) && activity.equals(o.activity) && updated == o.updated;
    }
}
