package io.github.grimmmyy.lumen;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A single home-screen tile, drawn by one View instead of a FrameLayout with
 * four children. Normally it draws exactly one pre-rendered bitmap (see
 * {@link ArtCache}); until that arrives it draws a rounded color block with the
 * title, so the layout is complete on the very first frame.
 *
 * Focus is shown by scaling (a RenderNode property: no relayout, no redraw of
 * the content) plus a white outline. No elevation: animating shadows is one of
 * the most expensive things a weak TV GPU can be asked to do.
 */
final class TileView extends View {
    static final float FOCUS_SCALE = 1.1f;
    private static final TimeInterpolator EASE = new DecelerateInterpolator(1.6f);

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint captionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Rect src = new Rect();
    private final float radius;
    private final float stroke;

    private Tile tile;
    private Bitmap art;
    private boolean cropArt; // posters: center-crop; app art is already the exact size
    private CharSequence fittedTitle;
    private CharSequence fittedCaption;

    TileView(Context ctx, float radiusPx) {
        super(ctx);
        radius = radiusPx;
        float d = ctx.getResources().getDisplayMetrics().density;
        stroke = 3 * d;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(stroke);
        strokePaint.setColor(Color.WHITE);
        trackPaint.setColor(0x55FFFFFF);
        barPaint.setColor(Color.WHITE);
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titlePaint.setTextAlign(Paint.Align.CENTER);
        captionPaint.setColor(0xCCFFFFFF);
        captionPaint.setTextAlign(Paint.Align.CENTER);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClickable(true);
        setLongClickable(true);
        // Layers are not needed: alpha is never animated on tiles.
        setWillNotDraw(false);
    }

    Tile tile() {
        return tile;
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
        titlePaint.setTextSize(h * 0.13f);
        captionPaint.setTextSize(h * 0.10f);
        fittedTitle = null;
        fittedCaption = null;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (tile == null) return;
        float w = bounds.width();
        float h = bounds.height();
        if (art != null) {
            if (cropArt) {
                centerCrop(art.getWidth(), art.getHeight(), w, h);
                // Posters arrive square-cornered and at any size; a rectangular draw keeps this cheap.
                c.drawBitmap(art, src, bounds, bitmapPaint);
            } else {
                c.drawBitmap(art, null, bounds, bitmapPaint);
            }
        } else {
            c.drawRoundRect(bounds, radius, radius, fillPaint);
        }

        boolean needsText = art == null || cropArt;
        if (needsText) {
            if (fittedTitle == null) {
                fittedTitle = TextUtils.ellipsize(tile.title, titlePaint, w * 0.88f, TextUtils.TruncateAt.END);
                fittedCaption = tile.caption == null ? null
                        : TextUtils.ellipsize(tile.caption, captionPaint, w * 0.88f, TextUtils.TruncateAt.END);
            }
            float baseline = cropArt ? h * 0.88f : (fittedCaption != null ? h * 0.50f : h * 0.56f);
            if (cropArt) {
                // keep text legible over a poster
                titlePaint.setShadowLayer(6, 0, 1, 0xCC000000);
            } else {
                titlePaint.clearShadowLayer();
            }
            c.drawText(fittedTitle, 0, fittedTitle.length(), w / 2f, baseline, titlePaint);
            if (fittedCaption != null && !cropArt) {
                c.drawText(fittedCaption, 0, fittedCaption.length(), w / 2f, baseline + h * 0.17f, captionPaint);
            }
        }

        if (tile.progress > 0f) {
            float barH = Math.max(3f, h * 0.035f);
            float inset = radius;
            float top = h - barH - h * 0.06f;
            c.drawRect(inset, top, w - inset, top + barH, trackPaint);
            c.drawRect(inset, top, inset + (w - 2 * inset) * tile.progress, top + barH, barPaint);
        }

        if (isFocused()) {
            float half = stroke / 2f;
            c.drawRoundRect(half, half, w - half, h - half, radius - half, radius - half, strokePaint);
        }
    }

    private void centerCrop(int bw, int bh, float vw, float vh) {
        float scale = Math.max(vw / bw, vh / bh);
        int cw = Math.round(vw / scale);
        int ch = Math.round(vh / scale);
        int x = (bw - cw) / 2;
        int y = (bh - ch) / 2;
        src.set(x, y, x + cw, y + ch);
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        float s = gained ? FOCUS_SCALE : 1f;
        animate().scaleX(s).scaleY(s)
                .setDuration(Motion.fast() ? 90 : 150)
                .setInterpolator(EASE)
                .start();
        invalidate(); // outline
    }

    /** Snaps scale to match focus after a rebind, without animating. */
    void syncFocusScale() {
        animate().cancel();
        float s = isFocused() ? FOCUS_SCALE : 1f;
        setScaleX(s);
        setScaleY(s);
    }
}
