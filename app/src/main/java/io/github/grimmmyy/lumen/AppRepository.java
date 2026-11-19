package io.github.grimmmyy.lumen;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds launchable apps. Call off the main thread: loading banners and icons is slow. */
final class AppRepository {
    private AppRepository() {}

    static List<AppEntry> load(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, AppEntry> byPkg = new LinkedHashMap<>();
        // TV apps first (they carry banners), then phone-style apps as a fallback.
        collect(ctx, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, byPkg);
        collect(ctx, pm, Intent.CATEGORY_LAUNCHER, byPkg);

        List<AppEntry> list = new ArrayList<>(byPkg.values());
        Collator c = Collator.getInstance();
        list.sort((a, b) -> c.compare(a.label, b.label));
        return list;
    }

    private static void collect(Context ctx, PackageManager pm, String category, Map<String, AppEntry> out) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> infos = pm.queryIntentActivities(query, 0);
        for (ResolveInfo ri : infos) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null) continue;
            String pkg = ai.packageName;
            if (out.containsKey(pkg) || pkg.equals(ctx.getPackageName())) continue;

            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));

            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setComponent(new ComponentName(pkg, ai.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            int color = dominantColor(banner != null ? banner : icon);
            out.put(pkg, new AppEntry(pkg, label, banner, icon, color, launch));
        }
    }

    /** Averages the opaque pixels of a drawable, then darkens it enough for white text. */
    static int dominantColor(Drawable d) {
        if (d == null) return 0xFF1B2333;
        try {
            Bitmap b = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(b);
            Rect old = d.copyBounds();
            d.setBounds(0, 0, 16, 9);
            d.draw(canvas);
            d.setBounds(old);

            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 9; y++) {
                for (int x = 0; x < 16; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    r += Color.red(p);
                    g += Color.green(p);
                    bl += Color.blue(p);
                    n++;
                }
            }
            b.recycle();
            if (n == 0) return 0xFF1B2333;

            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
            hsv[2] = Math.min(hsv[2], 0.45f);
            hsv[2] = Math.max(hsv[2], 0.18f);
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return 0xFF1B2333;
        }
    }
}
