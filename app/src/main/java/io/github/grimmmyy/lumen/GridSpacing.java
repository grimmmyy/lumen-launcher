package io.github.grimmmyy.lumen;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Gives grid tiles equal widths, a fixed gap between them, and the same side
 * margins as the rows, while header and row items keep the full width.
 */
final class GridSpacing extends RecyclerView.ItemDecoration {
    private final int side;
    private final int gap;

    GridSpacing(int sidePx, int gapPx) {
        side = sidePx;
        gap = gapPx;
    }

    /** Width of one grid tile for a list of the given width. */
    static int tileWidth(int listWidth, int side, int gap, int spans) {
        return (listWidth - 2 * side - (spans - 1) * gap) / spans;
    }

    @Override
    public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        if (!(view instanceof TileView) || !(view.getLayoutParams() instanceof GridLayoutManager.LayoutParams)) {
            out.setEmpty();
            return;
        }
        GridLayoutManager lm = (GridLayoutManager) parent.getLayoutManager();
        if (lm == null) return;
        GridLayoutManager.LayoutParams lp = (GridLayoutManager.LayoutParams) view.getLayoutParams();
        if (lp.getSpanSize() != 1) {
            out.setEmpty();
            return;
        }
        int spans = lm.getSpanCount();
        int col = lp.getSpanIndex();
        int width = parent.getWidth();
        int tile = tileWidth(width, side, gap, spans);
        float cell = width / (float) spans;
        int left = Math.round(side + col * (tile + gap) - col * cell);
        int right = Math.round(cell) - tile - left;
        out.set(left, gap / 2, right, gap / 2);
    }
}
