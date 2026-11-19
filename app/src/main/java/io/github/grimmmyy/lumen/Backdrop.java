package io.github.grimmmyy.lumen;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.Executor;

/**
 * The full-screen background, following focus.
 *
 * Sources, in order (<wallpapers> is /sdcard/Lumen/wallpapers):
 *   1. a per-app wallpaper   <wallpapers>/<package>.jpg|png|webp
 *   2. the focused poster    (Continue Watching)
 *   3. a default wallpaper   <wallpapers>/default.jpg|png|webp
 *   4. the app's own artwork, blurred (Apple TV style)
 *   5. a gradient from the app's color
 *
 * Performance: each backdrop is composited off the main thread into one
 * bitmap with the darkening scrim already baked in. The GPU then draws one
 * full-screen texture per frame (two only while crossfading) instead of an
 * image plus stacked gradient layers. Blurred art is composited at a quarter
 * of screen size: it is soft anyway, and that keeps it to under 1 MB.
 * Requests are debounced, and debounced harder while a key is held, so
 * scrolling fast never decodes images it will throw away.
 */
final class Backdrop {
    private static final String[] EXTS = {".jpg", ".png", ".webp"};
    private static final int FADE_MS = 300;
    private static final int PHOTO_W = 1280;
    private static final int PHOTO_H = 720;
    private static final int SOFT_W = 480;
    private static final int SOFT_H = 270;

    private final ImageView a;
    private final ImageView b;
    private final Executor io;
    private final Handler main;
    private final ImageLoader images;
    private final File dir;
    private final Runnable onFirstOpaque;
    private final LruCache<String, Bitmap> composed = new LruCache<String, Bitmap>(10 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private boolean topIsA = false;
    private boolean opaque;
    private String currentKey;
    private Runnable pending;

    Backdrop(ImageView a, ImageView b, Executor io, Handler main, ImageLoader images, File dir, Runnable onFirstOpaque) {
        this.a = a;
        this.b = b;
        this.io = io;
        this.main = main;
        this.images = images;
        this.dir = dir;
        this.onFirstOpaque = onFirstOpaque;
    }

    File folder() {
        return dir;
    }

    /** Forget cached composites, e.g. after wallpapers were added. */
    void invalidate() {
        composed.evictAll();
        currentKey = null;
    }

    void trim() {
        composed.evictAll();
    }

    /**
     * @param seed the tile's own artwork bitmap, used for the blurred backdrop; may be null
     */
    void request(String pkg, String imageUri, int color, Bitmap seed) {
        final String key = (pkg == null ? "" : pkg) + "|" + (imageUri == null ? "" : imageUri)
                + "|" + color + "|" + (seed != null);
        if (key.equals(currentKey)) return;
        currentKey = key;
        if (pending != null) main.removeCallbacks(pending);
        pending = () -> start(key, pkg, imageUri, color, seed);
        main.postDelayed(pending, Motion.fast() ? 280 : 110);
    }

    private void start(String key, String pkg, String imageUri, int color, Bitmap seed) {
        Bitmap hit = composed.get(key);
        if (hit != null) {
            show(hit);
            return;
        }
        io.execute(() -> {
            Bitmap out;
            try {
                out = compose(pkg, imageUri, color, seed);
            } catch (RuntimeException | OutOfMemoryError e) {
                out = null;
            }
            final Bitmap result = out;
            main.post(() -> {
                if (result == null) return;
                composed.put(key, result);
                if (key.equals(currentKey)) show(result);
            });
        });
    }

    // ---- Compositing (background thread) ------------------------------------

    private Bitmap compose(String pkg, String imageUri, int color, Bitmap seed) {
        File own = pkg == null ? null : find(pkg);
        Bitmap photo = own != null ? decodeFile(own, PHOTO_W) : null;
        if (photo == null && imageUri != null) photo = images.decodeSync(imageUri, PHOTO_W);
        if (photo == null) {
            File def = find("default");
            if (def != null) photo = decodeFile(def, PHOTO_W);
        }
        if (photo != null) {
            Bitmap out = cover(photo, PHOTO_W, PHOTO_H, null);
            photo.recycle();
            return finish(out);
        }
        if (seed != null && !seed.isRecycled()) return finish(blur(seed, color));
        return finish(gradient(color));
    }

    /** Scales a source to fill w x h (center crop), optionally through a color filter. */
    private static Bitmap cover(Bitmap src, int w, int h, ColorMatrixColorFilter filter) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        int cw = Math.round(w / scale);
        int ch = Math.round(h / scale);
        int x = (src.getWidth() - cw) / 2;
        int y = (src.getHeight() - ch) / 2;
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (filter != null) p.setColorFilter(filter);
        new Canvas(out).drawBitmap(src, new Rect(x, y, x + cw, y + ch), new RectF(0, 0, w, h), p);
        return out;
    }

    /**
     * A cheap, smooth blur: shrink to a few dozen pixels, then grow back in two
     * bilinear steps. Slightly darkened and more saturated, like tvOS.
     */
    private static Bitmap blur(Bitmap seed, int color) {
        // Two shrink steps average away logos and text; only the colors survive.
        Bitmap small = Bitmap.createScaledBitmap(seed, 32, 18, true);
        Bitmap tiny = Bitmap.createScaledBitmap(small, 8, 5, true);
        small.recycle();
        Bitmap mid = Bitmap.createScaledBitmap(tiny, 64, 36, true);
        tiny.recycle();
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(1.4f);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.42f, 0.42f, 0.42f, 1f); // white banners must still read as a dark room
        m.postConcat(dim);
        // Laid over a gradient of the app's own color, so a mostly-white banner
        // gives a tinted dark room rather than flat grey.
        Bitmap out = gradient(color);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(m));
        p.setAlpha(185);
        new Canvas(out).drawBitmap(mid, null, new RectF(0, 0, SOFT_W, SOFT_H), p);
        mid.recycle();
        return out;
    }

    private static Bitmap gradient(int color) {
        Bitmap out = Bitmap.createBitmap(SOFT_W, SOFT_H, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, SOFT_W, SOFT_H,
                ArtCache.darken(color, 0.05f), 0xFF06070B, Shader.TileMode.CLAMP));
        new Canvas(out).drawRect(0, 0, SOFT_W, SOFT_H, p);
        return out;
    }

    /** Bakes the darkening scrim into the image (top for the clock, bottom for the rows). */
    private static Bitmap finish(Bitmap base) {
        int w = base.getWidth();
        int h = base.getHeight();
        Canvas c = new Canvas(base);
        Paint v = new Paint();
        v.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x8C000000, 0x26000000, 0x59000000, 0xE0000000},
                new float[]{0f, 0.28f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, v);
        Paint l = new Paint();
        l.setShader(new LinearGradient(0, 0, w * 0.6f, 0, 0x73000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w * 0.6f, h, l);
        return base;
    }

    private File find(String name) {
        if (dir == null) return null;
        for (String ext : EXTS) {
            File f = new File(dir, name + ext);
            if (f.canRead()) return f;
        }
        return null;
    }

    private static Bitmap decodeFile(File f, int targetW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetW) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), d);
    }

    // ---- Display (main thread) ----------------------------------------------

    /**
     * Fades the new image in on top of the old one, which stays fully opaque
     * underneath. The screen is never partly transparent, so the window
     * background can be dropped once the first image is in.
     */
    private void show(Bitmap bmp) {
        ImageView in = topIsA ? b : a;
        ImageView under = topIsA ? a : b;
        in.animate().cancel();
        under.animate().cancel();
        under.setAlpha(1f);
        under.setZ(0f);
        in.setZ(1f);
        in.setImageBitmap(bmp);
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(Motion.SCROLL).withEndAction(() -> {
            under.setImageDrawable(null);
            if (!opaque) {
                opaque = true;
                if (onFirstOpaque != null) onFirstOpaque.run();
            }
        }).start();
        topIsA = !topIsA;
    }
}
