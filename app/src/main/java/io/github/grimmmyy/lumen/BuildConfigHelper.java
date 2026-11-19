package io.github.grimmmyy.lumen;

import android.content.Context;
import android.content.pm.PackageManager;

/** Reads the version name without relying on the generated BuildConfig class. */
final class BuildConfigHelper {
    private BuildConfigHelper() {}

    static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
