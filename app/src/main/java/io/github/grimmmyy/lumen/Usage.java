package io.github.grimmmyy.lumen;

import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Process;
import android.os.storage.StorageManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Optional usage access (granted once with ADB; Fire TV hides the setting).
 * With it, the Recent row also includes apps opened from elsewhere (Alexa,
 * another app, the Amazon home screen), and the app menu shows storage use.
 * Lumen reads only "when was each app last used" and app sizes.
 */
final class Usage {
    private Usage() {}

    static boolean granted(Context ctx) {
        AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        try {
            //noinspection deprecation (unsafeCheckOpNoThrow needs API 29; Lumen supports 26)
            return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName())
                    == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Package → last time used (ms), over the last two weeks. Background thread. */
    static Map<String, Long> lastUsed(Context ctx) {
        Map<String, Long> out = new HashMap<>();
        if (!granted(ctx)) return out;
        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        try {
            Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(now - 14L * 86_400_000L, now);
            for (Map.Entry<String, UsageStats> e : stats.entrySet()) {
                long t = e.getValue().getLastTimeUsed();
                if (t > 0 && e.getValue().getTotalTimeInForeground() > 2_000) out.put(e.getKey(), t);
            }
        } catch (RuntimeException ignored) {
            // no data
        }
        return out;
    }

    /** "App 120 MB · Data 45 MB · Cache 30 MB", or null without access. Background thread. */
    static String sizes(Context ctx, String pkg) {
        if (!granted(ctx)) return null;
        try {
            StorageStatsManager ssm = (StorageStatsManager) ctx.getSystemService(Context.STORAGE_STATS_SERVICE);
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            StorageStats s = ssm.queryStatsForUid(StorageManager.UUID_DEFAULT, ai.uid);
            return "App " + mb(s.getAppBytes()) + "  ·  Data " + mb(s.getDataBytes()) + "  ·  Cache " + mb(s.getCacheBytes());
        } catch (Exception e) {
            return null;
        }
    }

    private static String mb(long b) {
        return b >= 1L << 30 ? String.format(java.util.Locale.US, "%.1f GB", b / (double) (1L << 30))
                : Math.round(b / (double) (1L << 20)) + " MB";
    }
}
