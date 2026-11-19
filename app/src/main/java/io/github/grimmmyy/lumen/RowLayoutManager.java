package io.github.grimmmyy.lumen;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Horizontal layout for one row.
 *
 * Focus moves across the visible tiles without scrolling (cheapest: only two
 * tiles change). The row scrolls only when focus gets within half a tile of
 * an edge, and then always leaves half of the next tile peeking in, so you can
 * see there is more.
 */
final class RowLayoutManager extends LinearLayoutManager {
    RowLayoutManager(Context ctx) {
        super(ctx, HORIZONTAL, false);
        setItemPrefetchEnabled(true);
        setInitialPrefetchItemCount(5);
    }

    @Override
    protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extra) {
        int e = getWidth() / 3;
        extra[0] = e;
        extra[1] = e;
    }

    @Override
    public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                 @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
        int left = getDecoratedLeft(child);
        int right = getDecoratedRight(child);
        int peek = (right - left) / 2;
        int start = getPaddingLeft();
        int end = getWidth() - getPaddingRight();
        int dx = 0;
        if (right + peek > end) dx = right + peek - end;
        else if (left - peek < start) dx = left - peek - start;
        if (dx != 0) {
            if (immediate) parent.scrollBy(dx, 0);
            else parent.smoothScrollBy(dx, 0, Motion.SCROLL, Motion.scrollDuration());
        }
        return true;
    }
}
