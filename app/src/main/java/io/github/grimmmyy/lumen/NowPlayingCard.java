package io.github.grimmmyy.lumen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The Now Playing card: artwork, what's playing and on which app, and a thin
 * progress bar. One View, drawn directly (like TileView), so it costs a
 * handful of draw calls. While playing it redraws once a second for the
 * progress bar, and only while it's on screen.
 */
final class NowPlayingCard extends View {
    private static final float FOCUS_SCALE = 1.05f;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final RectF artBox = new RectF();
    private final Matrix m = new Matrix();
    private final Path playPath = new Path();
    private final float radius;
    private final float density;
    private NowPlaying.State state;
    private int tint = 0xFF1B2333;
    private float lift;
    private boolean shadersStale = true;
    private Bitmap shadedArt;

    NowPlayingCard(Context ctx, float radiusPx) {
        super(ctx);
        radius = radiusPx;
        density = ctx.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * density);
        ring.setColor(Color.WHITE);
        shadow.setColor(0x99000000);
        shadow.setMaskFilter(new BlurMaskFilter(14 * density, BlurMaskFilter.Blur.NORMAL));
        track.setColor(0x40FFFFFF);
        bar.setColor(Color.WHITE);
        glyph.setColor(0xFF9CC2FF);
        label.setColor(0xFF9CC2FF);
        label.setTypeface(Fonts.semibold(ctx));
        label.setLetterSpacing(0.08f);
        title.setColor(Color.WHITE);
        title.setTypeface(Fonts.semibold(ctx));
        sub.setColor(0xB3FFFFFF);
        sub.setTypeface(Fonts.regular(ctx));
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setTag("nowplaying");
        // A software layer for the blurred focus shadow is avoided: the shadow is a
        // plain blurred rect drawn only while focused, and the view is small.
    }

    NowPlaying.State state() {
        return state;
    }

    void setState(NowPlaying.State s) {
        boolean newArt = s == null || state == null || s.art != state.art;
        state = s;
        if (newArt) {
            tint = s != null && s.art != null ? ArtCache.dominantColor(
                    new android.graphics.drawable.BitmapDrawable(getResources(), s.art)) : 0xFF1B2333;
            shadersStale = true;
        }
        if (s != null) setContentDescription("Now playing: " + s.title + (s.subtitle != null ? ", " + s.subtitle : "")
                + ", on " + s.appLabel);
        invalidate();
    }

    /** The tint taken from the artwork, for the backdrop. */
    int tint() {
        return tint;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        box.set(0, 0, w, h);
        float pad = h * 0.10f;
        artBox.set(pad, pad, h - pad, h - pad);
        label.setTextSize(h * 0.085f);
        title.setTextSize(h * 0.15f);
        sub.setTextSize(h * 0.105f);
        shadersStale = true;
    }

    /** Gradient and artwork shaders, rebuilt only when the size or artwork changes. */
    private void buildShaders(float w, float h) {
        shadersStale = false;
        bg.setShader(new LinearGradient(0, 0, w, h, ArtCache.lighten(tint, 0.06f), ArtCache.darken(tint, 0.12f),
                Shader.TileMode.CLAMP));
        Bitmap a = state != null ? state.art : null;
        shadedArt = a;
        if (a == null || a.isRecycled()) {
            artPaint.setShader(null);
            return;
        }
        float side = artBox.width();
        float k = Math.max(side / a.getWidth(), side / a.getHeight());
        m.setScale(k, k);
        m.postTranslate(artBox.left + (side - a.getWidth() * k) / 2f, artBox.top + (side - a.getHeight() * k) / 2f);
        BitmapShader sh = new BitmapShader(a, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        sh.setLocalMatrix(m);
        artPaint.setShader(sh);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (lift > 0f) {
            shadow.setAlpha(Math.round(150 * lift));
            c.drawRoundRect(w * 0.03f, h * 0.1f + 8 * density * lift, w * 0.97f, h + 4 * density, radius, radius, shadow);
        }
        NowPlaying.State s = state;
        if (shadersStale || (s != null && s.art != shadedArt)) buildShaders(w, h);
        c.drawRoundRect(box, radius, radius, bg);

        float textLeft = artBox.right + h * 0.10f;
        if (s != null && s.art != null && !s.art.isRecycled() && artPaint.getShader() != null) {
            c.drawRoundRect(artBox, radius * 0.7f, radius * 0.7f, artPaint);
        } else {
            textLeft = h * 0.12f;
        }
        if (s == null) return;

        float right = w - h * 0.12f;
        float maxW = right - textLeft;
        float y = h * 0.30f;
        // Play/pause glyph before the label.
        float g = label.getTextSize() * 0.8f;
        drawGlyph(c, textLeft, y - g, g, s.playing);
        String head = (s.playing ? "NOW PLAYING" : "PAUSED") + "  ·  " + s.appLabel.toUpperCase(java.util.Locale.getDefault());
        CharSequence headFit = TextUtils.ellipsize(head, label, maxW - g * 1.6f, TextUtils.TruncateAt.END);
        c.drawText(headFit, 0, headFit.length(), textLeft + g * 1.6f, y, label);
        y += h * 0.22f;
        CharSequence t = TextUtils.ellipsize(s.title, title, maxW, TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), textLeft, y, title);
        if (s.subtitle != null) {
            y += h * 0.16f;
            CharSequence st = TextUtils.ellipsize(s.subtitle, sub, maxW, TextUtils.TruncateAt.END);
            c.drawText(st, 0, st.length(), textLeft, y, sub);
        }
        if (s.duration > 0) {
            float barY = h * 0.80f, barH = Math.max(3f, h * 0.022f);
            c.drawRoundRect(textLeft, barY, right, barY + barH, barH, barH, track);
            float f = Math.min(1f, s.positionNow() / (float) s.duration);
            c.drawRoundRect(textLeft, barY, textLeft + (right - textLeft) * f, barY + barH, barH, barH, bar);
            if (s.playing && isAttachedToWindow()) postInvalidateDelayed(1000);
        }
        if (lift > 0f) {
            ring.setAlpha(Math.round(255 * lift));
            float o = 3.5f * density + ring.getStrokeWidth() / 2f;
            c.drawRoundRect(-o, -o, w + o, h + o, radius + o, radius + o, ring);
        }
    }

    private void drawGlyph(Canvas c, float x, float top, float size, boolean playing) {
        if (playing) {
            // Pause bars: shows that it is playing (tvOS style).
            float bw = size * 0.3f;
            c.drawRect(x, top, x + bw, top + size, glyph);
            c.drawRect(x + size * 0.55f, top, x + size * 0.55f + bw, top + size, glyph);
        } else {
            playPath.reset();
            playPath.moveTo(x, top);
            playPath.lineTo(x + size * 0.9f, top + size / 2f);
            playPath.lineTo(x, top + size);
            playPath.close();
            c.drawPath(playPath, glyph);
        }
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        float s = gained ? FOCUS_SCALE : 1f;
        setTranslationZ(gained ? 1f : 0f);
        final float from = lift, to = gained ? 1f : 0f;
        animate().scaleX(s).scaleY(s).setDuration(Motion.fast() ? 90 : 160)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .setUpdateListener(a -> {
                    lift = from + (to - from) * a.getAnimatedFraction();
                    invalidate();
                })
                .withEndAction(() -> {
                    lift = to;
                    invalidate();
                }).start();
    }
}
