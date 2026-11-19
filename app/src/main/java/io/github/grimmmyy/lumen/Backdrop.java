package io.github.grimmmyy.lumen;

import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.widget.ImageView;

import java.io.File;

/**
 * The full-screen background. It changes with the focused tile:
 *   1. a per-app wallpaper  <wallpapers>/<package>.jpg|png|webp
 *   2. the focused poster   (Continue Watching)
 *   3. a default wallpaper  <wallpapers>/default.jpg|png|webp
 *   4. a gradient made from the app's own colors
 * Two image views crossfade so changes are smooth.
 */
final class Backdrop {
    private static final String[] EXTS = {".jpg", ".png", ".webp"};
    private static final long DEBOUNCE_MS = 180;
    private static final long FADE_MS = 350;

    private final ImageView a;
    private final ImageView b;
    private final Handler handler;
    private final ImageLoader images;
    private final File dir;
    private final Resources res;
    private final int screenWidth;

    private boolean showingA = true;
    private String currentKey;
    private Runnable pending;

    Backdrop(ImageView a, ImageView b, Handler handler, ImageLoader images, File dir, Resources res) {
        this.a = a;
        this.b = b;
        this.handler = handler;
        this.images = images;
        this.dir = dir;
        this.res = res;
        this.screenWidth = res.getDisplayMetrics().widthPixels;
    }

    File folder() {
        return dir;
    }

    void request(String pkg, int color, String imageUri) {
        final String key = pkg + "|" + imageUri + "|" + color;
        if (key.equals(currentKey)) return;
        currentKey = key;
        if (pending != null) handler.removeCallbacks(pending);
        pending = () -> resolve(key, pkg, color, imageUri);
        handler.postDelayed(pending, DEBOUNCE_MS);
    }

    /** Forces the next request to reload, e.g. after wallpapers were added. */
    void invalidate() {
        currentKey = null;
    }

    private void resolve(String key, String pkg, int color, String imageUri) {
        String src = null;
        File own = pkg == null ? null : find(pkg);
        if (own != null) {
            src = own.getAbsolutePath();
        } else if (imageUri != null) {
            src = imageUri;
        } else {
            File def = find("default");
            if (def != null) src = def.getAbsolutePath();
        }
        if (src == null) {
            show(gradient(color));
            return;
        }
        images.load(src, screenWidth, bmp -> {
            if (!key.equals(currentKey)) return; // focus moved on
            show(bmp != null ? new BitmapDrawable(res, bmp) : gradient(color));
        });
    }

    private File find(String name) {
        if (dir == null) return null;
        for (String ext : EXTS) {
            File f = new File(dir, name + ext);
            if (f.isFile()) return f;
        }
        return null;
    }

    private static Drawable gradient(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.min(hsv[2], 0.35f);
        int top = Color.HSVToColor(hsv);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{top, 0xFF07080C});
        return g;
    }

    private void show(Drawable d) {
        ImageView in = showingA ? b : a;
        ImageView out = showingA ? a : b;
        in.animate().cancel();
        out.animate().cancel();
        in.setImageDrawable(d);
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(FADE_MS).start();
        out.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> out.setImageDrawable(null)).start();
        showingA = !showingA;
    }
}
