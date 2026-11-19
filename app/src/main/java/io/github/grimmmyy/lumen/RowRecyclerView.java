package io.github.grimmmyy.lumen;

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

/**
 * A horizontal row that remembers which tile was focused last. When focus
 * comes back into the row from above or below, it returns to that tile
 * instead of whichever tile happens to be geometrically closest. Without
 * this, moving down and back up loses your place.
 */
final class RowRecyclerView extends RecyclerView {
    private int lastFocused;

    RowRecyclerView(Context ctx) {
        super(ctx);
    }

    int lastFocused() {
        return lastFocused;
    }

    void setLastFocused(int position) {
        lastFocused = Math.max(0, position);
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        int pos = getChildAdapterPosition(child);
        if (pos != NO_POSITION) lastFocused = pos;
    }

    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        if (!hasFocus()) {
            ViewHolder vh = findViewHolderForAdapterPosition(lastFocused);
            if (vh != null && vh.itemView.isFocusable() && vh.itemView.getVisibility() == VISIBLE) {
                views.add(vh.itemView);
                return;
            }
        }
        super.addFocusables(views, direction, focusableMode);
    }
}
