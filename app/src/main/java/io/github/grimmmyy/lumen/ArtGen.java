package io.github.grimmmyy.lumen;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Random;

/**
 * Generated backgrounds, drawn on the TV: no downloads, no licensing. Each is
 * rendered once into a small bitmap (soft shapes need no resolution) and then
 * scaled up like the blurred app art. The arrangement changes daily.
 */
final class ArtGen {
    static final String NONE = "app";
    static final String[] STYLES = {NONE, "aurora", "dusk", "ocean", "geometry"};
    static final String[] LABELS = {"Blur of the focused app", "Aurora", "Dusk", "Ocean", "Geometry"};
    private static final int W = 480, H = 270;

    private ArtGen() {}

    /** @param day changes the arrangement once a day */
    static Bitmap render(String style, long day) {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Random r = new Random(style.hashCode() * 31L + day);
        switch (style) {
            case "aurora":
                fill(c, 0xFF050913, 0xFF0B1426);
                blob(c, r, 0x8822D3A6, 0.55f);
                blob(c, r, 0x6631A0FF, 0.6f);
                blob(c, r, 0x55A24BFF, 0.5f);
                blob(c, r, 0x3322FFC8, 0.4f);
                break;
            case "dusk":
                fill(c, 0xFF2A1240, 0xFF0D0A1C);
                Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG);
                float sx = W * (0.25f + r.nextFloat() * 0.5f);
                sun.setShader(new RadialGradient(sx, H * 0.78f, W * 0.55f,
                        new int[]{0xCCFF8A4C, 0x55FF4E7A, 0x00FF4E7A}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, W, H, sun);
                blob(c, r, 0x44FFC26B, 0.35f);
                break;
            case "ocean":
                fill(c, 0xFF04161F, 0xFF06283A);
                for (int i = 0; i < 5; i++) {
                    Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
                    band.setStyle(Paint.Style.STROKE);
                    band.setStrokeWidth(18 + r.nextInt(30));
                    band.setColor(i % 2 == 0 ? 0x2238D6E0 : 0x1A2F7BFF);
                    android.graphics.Path p = new android.graphics.Path();
                    float y0 = H * (0.2f + 0.15f * i), amp = 10 + r.nextInt(25), ph = r.nextFloat() * 6f;
                    p.moveTo(0, y0);
                    for (int x = 0; x <= W; x += 12) p.lineTo(x, y0 + (float) Math.sin(x / 70f + ph) * amp);
                    c.drawPath(p, band);
                }
                blob(c, r, 0x5530C6D8, 0.6f);
                break;
            default: // geometry
                fill(c, 0xFF101218, 0xFF181B26);
                int[] palette = {0x33FF7A59, 0x3348A7FF, 0x33FFD166, 0x339B7BFF, 0x3330D9A6};
                for (int i = 0; i < 7; i++) {
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setColor(palette[r.nextInt(palette.length)]);
                    float cx = r.nextFloat() * W, cy = r.nextFloat() * H, rad = 40 + r.nextFloat() * 120;
                    if (r.nextBoolean()) c.drawCircle(cx, cy, rad, p);
                    else c.drawRoundRect(cx - rad, cy - rad * 0.6f, cx + rad, cy + rad * 0.6f, 24, 24, p);
                }
                break;
        }
        // Soften everything: shrink and grow back (the same cheap blur as app art).
        Bitmap small = Bitmap.createScaledBitmap(b, W / 6, H / 6, true);
        b.recycle();
        Bitmap soft = Bitmap.createScaledBitmap(small, W, H, true);
        small.recycle();
        return soft;
    }

    private static void fill(Canvas c, int top, int bottom) {
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, W * 0.3f, H, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
    }

    private static void blob(Canvas c, Random r, int color, float size) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = r.nextFloat() * W, cy = r.nextFloat() * H * 0.8f, rad = W * size * (0.6f + r.nextFloat() * 0.5f);
        p.setShader(new RadialGradient(cx, cy, rad, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rad, p);
    }
}
