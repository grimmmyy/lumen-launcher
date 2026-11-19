package io.github.grimmmyy.lumen;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Tile artwork, rendered once and reused.
 *
 * Every app's banner (or icon plus label, for apps without a TV banner) is
 * drawn into a bitmap at the exact tile size with the rounded corners baked
 * in. Tiles then draw a single bitmap with no scaling and no clipping, which
 * is the cheapest thing a weak TV GPU can do. Rendered art is written to
 * disk, so a cold start never has to open other apps' APKs to draw the home
 * screen.
 */
final class ArtCache {
    interface Callback {
        void onArt(Bitmap art); // main thread; null if the app vanished
    }

    private static final int VERSION = 1; // bump when rendering changes
    private static final int DEFAULT_COLOR = 0xFF1B2333;

    private final Context ctx;
    private final Executor io;
    private final Handler main;
    private final File dir;
    private final float radius;
    private final LruCache<String, Bitmap> memory;
    private final Map<String, List<Callback>> pending = new HashMap<>();
    private final SharedPreferences colors;
    private final Map<String, Integer> colorMemo = new HashMap<>();

    ArtCache(Context ctx, Executor io, Handler main, float radiusPx) {
        this.ctx = ctx.getApplicationContext();
        this.io = io;
        this.main = main;
        this.radius = radiusPx;
        this.dir = new File(ctx.getCacheDir(), "art");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        this.colors = ctx.getSharedPreferences("art_colors", Context.MODE_PRIVATE);
        int max = (int) Math.min(Runtime.getRuntime().maxMemory() / 6, 20L * 1024 * 1024);
        memory = new LruCache<String, Bitmap>(max) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    /** Memory hit only; never blocks. */
    Bitmap peek(AppEntry e, int w, int h) {
        return memory.get(key(e, w, h));
    }

    /** The app's dominant color, known once its art has been rendered at least once. */
    int color(AppEntry e) {
        String k = e.pkg + "@" + e.updated;
        synchronized (colorMemo) {
            Integer c = colorMemo.get(k);
            if (c != null) return c;
        }
        int c = colors.getInt(k, DEFAULT_COLOR);
        synchronized (colorMemo) {
            colorMemo.put(k, c);
        }
        return c;
    }

    void load(AppEntry e, int w, int h, Callback cb) {
        final String key = key(e, w, h);
        Bitmap hit = memory.get(key);
        if (hit != null) {
            cb.onArt(hit);
            return;
        }
        synchronized (pending) {
            List<Callback> waiting = pending.get(key);
            if (waiting != null) {
                waiting.add(cb);
                return;
            }
            List<Callback> list = new ArrayList<>();
            list.add(cb);
            pending.put(key, list);
        }
        io.execute(() -> {
            Bitmap bmp = fromDisk(key);
            if (bmp == null) {
                bmp = render(e, w, h);
                if (bmp != null) toDisk(key, bmp);
            }
            if (bmp != null) memory.put(key, bmp);
            final Bitmap result = bmp;
            main.post(() -> {
                List<Callback> list;
                synchronized (pending) {
                    list = pending.remove(key);
                }
                if (list != null) for (Callback c : list) c.onArt(result);
            });
        });
    }

    /** Pre-renders art in the background so the first scroll never waits. */
    void warm(List<AppEntry> apps, int w, int h) {
        for (AppEntry e : apps) {
            if (memory.get(key(e, w, h)) == null) load(e, w, h, art -> { });
        }
    }

    /** Deletes cached art for apps that were removed or updated. Background thread. */
    void prune(List<AppEntry> apps) {
        Set<String> live = new HashSet<>();
        for (AppEntry e : apps) live.add(e.pkg + "_" + e.updated + "_");
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            boolean keep = false;
            for (String prefix : live) {
                if (n.startsWith(prefix)) {
                    keep = n.endsWith("_v" + VERSION + ".png");
                    break;
                }
            }
            //noinspection ResultOfMethodCallIgnored
            if (!keep) f.delete();
        }
    }

    /**
     * Shrinks the in-memory cache to a third while Lumen is hidden, keeping
     * {@code keep} (the art on screen): it is marked most recently used first,
     * so the screen comes back without placeholders even if it was rebuilt.
     */
    void trim(java.util.Set<Bitmap> keep) {
        for (Map.Entry<String, Bitmap> e : memory.snapshot().entrySet()) {
            if (keep.contains(e.getValue())) memory.get(e.getKey());
        }
        memory.trimToSize(memory.maxSize() / 3);
    }

    private static String key(AppEntry e, int w, int h) {
        return e.pkg + "_" + e.updated + "_" + w + "x" + h + "_v" + VERSION;
    }

    private Bitmap fromDisk(String key) {
        File f = new File(dir, key + ".png");
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    private void toDisk(String key, Bitmap bmp) {
        File tmp = new File(dir, key + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception ignored) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(new File(dir, key + ".png"));
    }

    private Bitmap render(AppEntry e, int w, int h) {
        PackageManager pm = ctx.getPackageManager();
        Drawable banner;
        Drawable icon;
        try {
            ActivityInfo ai = pm.getActivityInfo(e.component(), 0);
            banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            icon = banner == null ? ai.loadIcon(pm) : null;
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            return null;
        }

        int color = dominantColor(banner != null ? banner : icon);
        colors.edit().putInt(e.pkg + "@" + e.updated, color).apply();
        synchronized (colorMemo) {
            colorMemo.put(e.pkg + "@" + e.updated, color);
        }

        // 1. Content, square-cornered.
        Bitmap content = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(content);
        if (banner != null) {
            c.drawColor(color);
            banner.setBounds(0, 0, w, h);
            banner.draw(c);
        } else {
            Paint bg = new Paint();
            bg.setShader(new LinearGradient(0, 0, 0, h, lighten(color, 0.10f), darken(color, 0.25f), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, bg);
            int size = Math.round(h * 0.42f);
            int left = (w - size) / 2;
            int top = Math.round(h * 0.17f);
            if (icon != null) {
                icon.setBounds(left, top, left + size, top + size);
                icon.draw(c);
            }
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(Color.WHITE);
            tp.setTextSize(h * 0.115f);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tp.setTextAlign(Paint.Align.CENTER);
            CharSequence label = TextUtils.ellipsize(e.label, tp, w * 0.88f, TextUtils.TruncateAt.END);
            c.drawText(label, 0, label.length(), w / 2f, h * 0.86f, tp);
        }

        // 2. Rounded, antialiased corners baked in (a software clipPath would be jagged).
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawRoundRect(new RectF(0, 0, w, h), radius, radius, p);
        content.recycle();
        return out;
    }

    /** Averages the opaque pixels, then keeps the result dark enough for white text. */
    static int dominantColor(Drawable d) {
        if (d == null) return DEFAULT_COLOR;
        try {
            Bitmap b = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(b);
            d.setBounds(0, 0, 16, 9);
            d.draw(canvas);
            long r = 0, g = 0, bl = 0, n = 0;
            int[] px = new int[16 * 9];
            b.getPixels(px, 0, 16, 0, 0, 16, 9);
            b.recycle();
            for (int p : px) {
                if (Color.alpha(p) < 128) continue;
                r += Color.red(p);
                g += Color.green(p);
                bl += Color.blue(p);
                n++;
            }
            if (n == 0) return DEFAULT_COLOR;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
            hsv[2] = Math.max(0.18f, Math.min(hsv[2], 0.45f));
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return DEFAULT_COLOR;
        }
    }

    static int lighten(int color, float amount) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.min(1f, hsv[2] + amount);
        return Color.HSVToColor(hsv);
    }

    static int darken(int color, float amount) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0f, hsv[2] - amount);
        return Color.HSVToColor(hsv);
    }
}
