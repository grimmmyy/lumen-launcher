package io.github.grimmmyy.lumen;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.function.IntPredicate;

/**
 * Vertical layout for the whole home screen.
 *
 * Default RecyclerView behavior scrolls "just enough" to show the focused
 * item, so focus drifts to the bottom edge and every press moves the page by a
 * different amount. Here the focused row's top always settles on one keyline,
 * so the eye stays in one place and every press moves the page the same
 * distance. Rows at the top of the page instead scroll fully to the top, so
 * the header (clock, title) comes back.
 */
final class HomeLayoutManager extends GridLayoutManager {
    private final float keyline;
    private final IntPredicate snapsToTop;

    HomeLayoutManager(Context ctx, int spans, float keylineFraction, IntPredicate snapsToTop) {
        super(ctx, spans);
        this.keyline = keylineFraction;
        this.snapsToTop = snapsToTop;
        setItemPrefetchEnabled(true);
        setInitialPrefetchItemCount(4);
    }

    @Override
    protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extra) {
        // Lay out half a screen beyond each edge so the next row is ready
        // (and focusable) before a key press needs it.
        int e = getHeight() / 2;
        extra[0] = e;
        extra[1] = e;
    }

    @Override
    public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                 @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
        int pos = getPosition(child);
        int dy;
        if (snapsToTop.test(pos)) {
            View first = findViewByPosition(0);
            if (first == null) {
                parent.smoothScrollToPosition(0);
                return true;
            }
            dy = getDecoratedTop(first) - getPaddingTop();
        } else {
            dy = getDecoratedTop(child) - Math.round(getHeight() * keyline);
        }
        if (dy != 0) {
            if (immediate) parent.scrollBy(0, dy);
            else parent.smoothScrollBy(0, dy, Motion.SCROLL, Motion.scrollDuration());
        }
        return true;
    }
}
