package io.github.grimmmyy.lumen;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

final class TileAdapter extends RecyclerView.Adapter<TileAdapter.VH> {
    interface Host {
        void onTileFocused(Tile tile);

        ImageLoader images();
    }

    private static final float FOCUS_SCALE = 1.1f;

    private final List<Tile> items;
    private final int width;
    private final int height;
    private final float radius;
    private final Host host;

    TileAdapter(List<Tile> items, int widthPx, int heightPx, float radiusPx, Host host) {
        this.items = items;
        this.width = widthPx;
        this.height = heightPx;
        this.radius = radiusPx;
        this.host = host;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tile, parent, false);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = width;
        lp.height = height;
        v.setLayoutParams(lp);
        v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        v.setClipToOutline(true);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH vh, int position) {
        Tile t = items.get(position);
        View root = vh.itemView;
        root.setTag(t.key);
        root.setContentDescription(t.title);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        bg.setColor(t.color);
        root.setBackground(bg);

        vh.image.setImageDrawable(null);
        vh.image.setTag(null);
        vh.icon.setVisibility(View.GONE);
        vh.label.setVisibility(View.GONE);

        if (t.banner != null) {
            vh.image.setImageDrawable(t.banner);
        } else if (t.imageUri != null) {
            vh.label.setText(t.title);
            vh.label.setVisibility(View.VISIBLE);
            final String uri = t.imageUri;
            vh.image.setTag(uri);
            host.images().load(uri, width, bmp -> {
                if (bmp != null && uri.equals(vh.image.getTag())) vh.image.setImageBitmap(bmp);
            });
        } else {
            vh.icon.setImageDrawable(t.icon);
            vh.icon.setVisibility(View.VISIBLE);
            vh.label.setText(t.title);
            vh.label.setVisibility(View.VISIBLE);
        }

        if (t.progress > 0f) {
            vh.progress.setProgress((int) (t.progress * 1000));
            vh.progress.setVisibility(View.VISIBLE);
        } else {
            vh.progress.setVisibility(View.GONE);
        }

        root.setOnClickListener(v -> {
            if (t.onClick != null) t.onClick.run();
        });
        root.setOnLongClickListener(v -> {
            if (t.onLongClick == null) return false;
            t.onLongClick.run();
            return true;
        });
        root.setOnFocusChangeListener((v, hasFocus) -> {
            float s = hasFocus ? FOCUS_SCALE : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(hasFocus ? 12f : 0f).setDuration(150).start();
            if (hasFocus) host.onTileFocused(t);
        });
        boolean focused = root.isFocused();
        root.setScaleX(focused ? FOCUS_SCALE : 1f);
        root.setScaleY(focused ? FOCUS_SCALE : 1f);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView image;
        final ImageView icon;
        final TextView label;
        final ProgressBar progress;

        VH(View v) {
            super(v);
            image = v.findViewById(R.id.image);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            progress = v.findViewById(R.id.progress);
        }
    }
}
