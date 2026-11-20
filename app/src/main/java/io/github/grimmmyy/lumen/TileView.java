package io.github.grimmmyy.lumen;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A single home-screen tile, drawn by one View instead of a FrameLayout with
 * four children. Normally it draws exactly one pre-rendered bitmap (see
 * {@link ArtCache}); until that arrives it draws a rounded color block with the
 * title, so the layout is complete on the very first frame.
 *
 * Focus "lifts" the tile: a 1.1x zoom (a RenderNode property, no redraw), a
 * soft shadow, and a white ring drawn just outside the tile with a gap, so it
 * stays visible on white artwork. The shadow is a pre-blurred alpha bitmap,
 * made once per tile size and drawn only for the focused tile; real elevation
 * shadows are recomputed every frame and are far too costly on a TV GPU.
 */
final class TileView extends View {
    static final float FOCUS_SCALE = 1.1f;
    private static final TimeInterpolator EASE = new DecelerateInterpolator(1.6f);
    private static final LruCache<String, Bitmap> SHADOWS = new LruCache<>(6);

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint captionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final RectF ring = new RectF();
    private final Rect src = new Rect();
    private final float radius;
    private final float ringGap;
    private final float ringWidth;
    private final float shadowPad;
    private final float shadowDrop;

    private Tile tile;
    private Bitmap art;
    private Bitmap shadow;
    private boolean cropArt; // posters: center-crop; app art is already the exact size
    private CharSequence fittedTitle;
    private CharSequence fittedCaption;
    private float lift; // 0 = resting, 1 = focused
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix sheenMatrix = new android.graphics.Matrix();
    private float sheen = -1f; // light sweep progress 0..1 while running, -1 when idle
    private android.animation.ValueAnimator sheenAnim;
    private boolean launching;

    TileView(Context ctx, float radiusPx) {
        super(ctx);
        radius = radiusPx;
        float d = ctx.getResources().getDisplayMetrics().density;
        ringGap = 3.5f * d;
        ringWidth = 2.5f * d;
        shadowPad = 18 * d;
        shadowDrop = 8 * d;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(ringWidth);
        ringPaint.setColor(Color.WHITE);
        shadowPaint.setColor(Color.BLACK);
        trackPaint.setColor(0x55FFFFFF);
        barPaint.setColor(Color.WHITE);
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTypeface(Fonts.medium(ctx));
        titlePaint.setTextAlign(Paint.Align.CENTER);
        captionPaint.setColor(0xB3FFFFFF);
        captionPaint.setTypeface(Fonts.regular(ctx));
        captionPaint.setTextAlign(Paint.Align.CENTER);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClickable(true);
        setLongClickable(true);
    }

    Tile tile() {
        return tile;
    }

    Bitmap art() {
        return art;
    }

    /** The app artwork, for the blurred backdrop. Null for posters and text tiles. */
    Bitmap backdropSeed() {
        return cropArt ? null : art;
    }

    void bind(Tile t) {
        tile = t;
        art = null;
        cropArt = false;
        fittedTitle = null;
        fittedCaption = null;
        fillPaint.setColor(t.color);
        setTag(t.key);
        setContentDescription(t.subtitle != null ? t.title + ", " + t.subtitle : t.title);
        invalidate();
    }

    void setArt(Bitmap bmp, boolean crop) {
        art = bmp;
        cropArt = crop;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightSpec)
                : Math.round(w * 9f / 16f);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        bounds.set(0, 0, w, h);
        float o = ringGap + ringWidth / 2f;
        ring.set(-o, -o, w + o, h + o);
        titlePaint.setTextSize(h * 0.135f);
        captionPaint.setTextSize(h * 0.10f);
        fittedTitle = null;
        fittedCaption = null;
        shadow = shadowFor(w, h);
        // A soft diagonal band of light, moved across the tile by its local matrix.
        sheenPaint.setShader(new android.graphics.LinearGradient(0, 0, w * 0.45f, h,
                new int[]{0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
        setCameraDistance(10_000 * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (tile == null) return;
        float w = bounds.width();
        float h = bounds.height();

        if (lift > 0f && shadow != null) {
            shadowPaint.setAlpha(Math.round(150 * lift));
            c.drawBitmap(shadow, -shadowPad, -shadowPad + shadowDrop * lift, shadowPaint);
        }

        if (art != null) {
            if (cropArt) {
                centerCrop(art.getWidth(), art.getHeight(), w, h);
                c.drawBitmap(art, src, bounds, bitmapPaint);
            } else {
                c.drawBitmap(art, null, bounds, bitmapPaint);
            }
        } else {
            c.drawRoundRect(bounds, radius, radius, fillPaint);
        }

        if (art == null || cropArt) drawText(c, w, h);

        if (tile.badge != null) drawBadge(c, w, h, tile.badge);

        if (tile.progress > 0f) {
            float barH = Math.max(3f, h * 0.035f);
            float inset = radius;
            float top = h - barH - h * 0.06f;
            c.drawRect(inset, top, w - inset, top + barH, trackPaint);
            c.drawRect(inset, top, inset + (w - 2 * inset) * tile.progress, top + barH, barPaint);
        }

        if (sheen >= 0f) {
            sheenMatrix.setTranslate(-w * 0.6f + w * 1.8f * sheen, 0);
            sheenPaint.getShader().setLocalMatrix(sheenMatrix);
            c.drawRoundRect(bounds, radius, radius, sheenPaint);
        }

        if (lift > 0f) {
            ringPaint.setAlpha(Math.round(255 * lift));
            float r = radius + ringGap + ringWidth / 2f;
            c.drawRoundRect(ring, r, r, ringPaint);
        }
    }

    private void drawText(Canvas c, float w, float h) {
        if (fittedTitle == null) {
            fittedTitle = TextUtils.ellipsize(tile.title, titlePaint, w * 0.86f, TextUtils.TruncateAt.END);
            fittedCaption = tile.caption == null ? null
                    : TextUtils.ellipsize(tile.caption, captionPaint, w * 0.86f, TextUtils.TruncateAt.END);
        }
        if (cropArt) {
            titlePaint.setShadowLayer(6, 0, 1, 0xCC000000); // legible over a poster
            c.drawText(fittedTitle, 0, fittedTitle.length(), w / 2f, h * 0.88f, titlePaint);
            titlePaint.clearShadowLayer();
            return;
        }
        Drawable icon = tile.icon;
        float titleBase;
        if (icon != null) {
            int size = Math.round(h * 0.30f);
            int left = Math.round((w - size) / 2f);
            int top = Math.round(h * (fittedCaption != null ? 0.15f : 0.22f));
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(c);
            titleBase = top + size + h * 0.20f;
        } else {
            titleBase = fittedCaption != null ? h * 0.50f : h * 0.56f;
        }
        c.drawText(fittedTitle, 0, fittedTitle.length(), w / 2f, titleBase, titlePaint);
        if (fittedCaption != null) {
            c.drawText(fittedCaption, 0, fittedCaption.length(), w / 2f, titleBase + h * 0.15f, captionPaint);
        }
    }

    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgeText = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    /** A small accent pill in the top-right corner. */
    private void drawBadge(Canvas c, float w, float h, String text) {
        badgeText.setTextSize(h * 0.085f);
        badgeText.setTypeface(Fonts.semibold(getContext()));
        badgeText.setColor(Color.WHITE);
        badgeText.setLetterSpacing(0.06f);
        float tw = badgeText.measureText(text);
        float ph = h * 0.05f, pv = h * 0.03f, m = h * 0.07f;
        float bh = badgeText.getTextSize() + 2 * pv;
        float right = w - m, left = right - tw - 2 * ph, top = m;
        badgePaint.setColor(0xFF3D7BFF);
        c.drawRoundRect(left, top, right, top + bh, bh / 2f, bh / 2f, badgePaint);
        c.drawText(text, left + ph, top + pv - badgeText.ascent() * 0.92f, badgeText);
    }

    private void centerCrop(int bw, int bh, float vw, float vh) {
        float scale = Math.max(vw / bw, vh / bh);
        int cw = Math.round(vw / scale);
        int ch = Math.round(vh / scale);
        int x = (bw - cw) / 2;
        int y = (bh - ch) / 2;
        src.set(x, y, x + cw, y + ch);
    }

    /** One blurred rounded-rect alpha mask per tile size, shared by every tile of that size. */
    private Bitmap shadowFor(int w, int h) {
        if (w <= 0 || h <= 0) return null;
        String key = w + "x" + h;
        Bitmap cached = SHADOWS.get(key);
        if (cached != null) return cached;
        int pad = Math.round(shadowPad);
        Bitmap b = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(pad * 0.6f, BlurMaskFilter.Blur.NORMAL));
        float inset = pad + w * 0.04f; // a little narrower than the tile, like a real shadow
        new Canvas(b).drawRoundRect(inset, pad + h * 0.06f, w + 2 * pad - inset, h + pad, radius, radius, p);
        SHADOWS.put(key, b);
        return b;
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        float s = gained ? FOCUS_SCALE : 1f;
        // Draw the focused tile above its neighbors. No background means no
        // outline, so this reorders drawing without casting a real shadow.
        setTranslationZ(gained ? 1f : 0f);
        final float from = lift;
        final float to = gained ? 1f : 0f;
        boolean effects = Motion.effects && !Motion.fast();
        if (gained && effects) {
            // Swing in slightly from the side focus came from, then settle (a
            // RenderNode transform: no redraw). Only for moves along a row: up
            // and down scroll the whole page, and a tilted tile on top of that
            // added render-thread time (measured on a Fire TV Stick).
            if (direction == FOCUS_RIGHT) setRotationY(7f);
            else if (direction == FOCUS_LEFT) setRotationY(-7f);
            startSheen();
        } else if (!gained) {
            stopSheen();
        }
        animate().scaleX(s).scaleY(s).rotationX(0f).rotationY(0f)
                .setDuration(Motion.fast() ? 90 : 160)
                .setInterpolator(EASE)
                .setUpdateListener(a -> {
                    lift = from + (to - from) * a.getAnimatedFraction();
                    invalidate();
                })
                .withEndAction(() -> {
                    lift = to;
                    invalidate();
                })
                .start();
    }

    private void startSheen() {
        stopSheen();
        sheenAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        sheenAnim.setStartDelay(90);
        sheenAnim.setDuration(620);
        sheenAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        sheenAnim.addUpdateListener(a -> {
            sheen = (float) a.getAnimatedValue();
            invalidate();
        });
        sheenAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                sheen = -1f;
                invalidate();
            }
        });
        sheenAnim.start();
    }

    private void stopSheen() {
        if (sheenAnim != null) sheenAnim.cancel();
        sheenAnim = null;
        sheen = -1f;
    }

    /** Grows the tile as its app opens; undone when Lumen is shown again. */
    void zoomForLaunch() {
        if (!Motion.effects) return;
        launching = true;
        animate().scaleX(1.22f).scaleY(1.22f).setDuration(240)
                .setInterpolator(new DecelerateInterpolator(2f)).setUpdateListener(null).withEndAction(null).start();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && launching) {
            launching = false;
            syncFocusScale();
        }
    }

    /** Snaps zoom and lift to match focus after a rebind, without animating. */
    void syncFocusScale() {
        animate().cancel();
        stopSheen();
        setRotationX(0f);
        setRotationY(0f);
        boolean f = isFocused();
        float s = f ? FOCUS_SCALE : 1f;
        setScaleX(s);
        setScaleY(s);
        setTranslationZ(f ? 1f : 0f);
        lift = f ? 1f : 0f;
        invalidate();
    }
}
