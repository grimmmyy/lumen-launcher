package io.github.grimmmyy.lumen;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The installed-app list, built for a fast home screen:
 *
 *  - memory: kept for the life of the process, so returning Home costs nothing
 *  - snapshot: a small file, so a cold start can draw tiles before asking
 *    PackageManager anything (loading labels from other APKs is the slow part)
 *  - change detection: PackageManager.getChangedPackages() tells us cheaply
 *    whether anything was installed, updated or removed since the last scan
 *
 * Scanning is the only slow call and must run off the main thread.
 */
final class AppCatalog {
    private static final String SNAPSHOT = "apps.tsv";
    private static final String META = "catalog_meta";
    private static final String KEY_SEQ = "seq";
    private static final String KEY_BOOT = "boot";

    private static List<AppEntry> memory;
    private static volatile boolean dirty;

    private AppCatalog() {}

    /** Instant: the list from memory, or the disk snapshot. Null only on the very first run. */
    static synchronized List<AppEntry> quick(Context ctx) {
        if (memory == null) memory = readSnapshot(ctx);
        return memory;
    }

    /** Marks the list stale (package broadcast received). */
    static void markDirty() {
        dirty = true;
    }

    /** Cheap check, safe on any thread: has anything changed since the last scan? */
    static boolean needsScan(Context ctx) {
        if (dirty) return true;
        synchronized (AppCatalog.class) {
            if (memory == null) return true;
        }
        SharedPreferences meta = ctx.getSharedPreferences(META, Context.MODE_PRIVATE);
        int boot = bootCount(ctx);
        // Sequence numbers restart at every boot, so a new boot means a full scan.
        if (boot < 0 || meta.getInt(KEY_BOOT, -2) != boot) return true;
        ChangedPackages changed = ctx.getPackageManager().getChangedPackages(meta.getInt(KEY_SEQ, 0));
        if (changed == null) return false;
        // Lumen isn't in its own list, so its own updates don't need a rescan.
        for (String pkg : changed.getPackageNames()) if (!pkg.equals(ctx.getPackageName())) return true;
        return false;
    }

    /** True if a list is already on screen from memory or the snapshot. */
    static synchronized boolean hasList() {
        return memory != null && !memory.isEmpty();
    }

    /**
     * Full scan. Slow: call off the main thread. Returns the new list, or null
     * when nothing visible changed (so callers can skip a redraw).
     */
    static List<AppEntry> scan(Context ctx) {
        dirty = false;
        PackageManager pm = ctx.getPackageManager();
        // Record where we are *before* scanning, so a change during the scan is seen next time.
        ChangedPackages now = pm.getChangedPackages(0);
        int seq = now != null ? now.getSequenceNumber() : 0;

        List<AppEntry> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collect(ctx, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, list, seen);
        collect(ctx, pm, Intent.CATEGORY_LAUNCHER, list, seen);
        Collator c = Collator.getInstance();
        Collections.sort(list, (a, b) -> c.compare(a.label, b.label));

        ctx.getSharedPreferences(META, Context.MODE_PRIVATE).edit()
                .putInt(KEY_SEQ, seq).putInt(KEY_BOOT, bootCount(ctx)).apply();

        synchronized (AppCatalog.class) {
            if (memory != null && same(memory, list)) return null;
            memory = Collections.unmodifiableList(list);
        }
        writeSnapshot(ctx, list);
        return memory;
    }

    private static void collect(Context ctx, PackageManager pm, String category,
                                List<AppEntry> out, Set<String> seen) {
        List<ResolveInfo> infos = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
        for (ResolveInfo ri : infos) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || seen.contains(ai.packageName) || ai.packageName.equals(ctx.getPackageName())) continue;
            seen.add(ai.packageName);
            long updated = 0;
            try {
                updated = pm.getPackageInfo(ai.packageName, 0).lastUpdateTime;
            } catch (PackageManager.NameNotFoundException ignored) {
                // raced with an uninstall; keep 0
            }
            String label = String.valueOf(ri.loadLabel(pm)).replace('\t', ' ').replace('\n', ' ');
            out.add(new AppEntry(ai.packageName, label, ai.name, category, updated));
        }
    }

    private static boolean same(List<AppEntry> a, List<AppEntry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).sameAs(b.get(i))) return false;
        return true;
    }

    private static int bootCount(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private static List<AppEntry> readSnapshot(Context ctx) {
        File f = new File(ctx.getFilesDir(), SNAPSHOT);
        if (!f.isFile()) return null;
        List<AppEntry> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length != 5) continue;
                out.add(new AppEntry(p[0], p[1], p[2], p[3], Long.parseLong(p[4])));
            }
        } catch (Exception e) {
            return null; // corrupt snapshot: fall back to a scan
        }
        return Collections.unmodifiableList(out);
    }

    private static void writeSnapshot(Context ctx, List<AppEntry> list) {
        File tmp = new File(ctx.getFilesDir(), SNAPSHOT + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (AppEntry e : list) {
                w.write(e.pkg + "\t" + e.label + "\t" + e.activity + "\t" + e.category + "\t" + e.updated + "\n");
            }
        } catch (Exception e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(new File(ctx.getFilesDir(), SNAPSHOT));
    }
}
