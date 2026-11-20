package io.github.grimmmyy.lumen;

import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole home screen as one virtualized list: header, section titles,
 * horizontal rows, and grid tiles. Only what is on screen (plus a margin) is
 * ever bound, and horizontal rows share one pool of tile views.
 */
final class HomeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    static final int HEADER = 0;
    static final int TITLE = 1;
    static final int ROW = 2;
    static final int TILE = 3;
    static final int NOW = 4;

    interface Host {
        void bindHeader(View header);

        void bindArt(TileView view, Tile tile, int width, int height);

        void onTileFocused(TileView view);

        void bindNowPlaying(NowPlayingCard card);
    }

    static final class Item {
        final int type;
        final String id;
        String text;
        List<Tile> tiles;
        int tileW;
        int tileH;
        Tile tile;

        Item(int type, String id) {
            this.type = type;
            this.id = id;
        }

        static Item header() {
            return new Item(HEADER, "header");
        }

        static Item title(String id, String text) {
            Item i = new Item(TITLE, "title:" + id);
            i.text = text;
            return i;
        }

        static Item row(String id, List<Tile> tiles, int w, int h) {
            Item i = new Item(ROW, "row:" + id);
            i.tiles = tiles;
            i.tileW = w;
            i.tileH = h;
            return i;
        }

        static Item now(int w, int h) {
            Item i = new Item(NOW, "now");
            i.tileW = w;
            i.tileH = h;
            return i;
        }

        static Item tile(Tile t, int w, int h) {
            Item i = new Item(TILE, "tile:" + t.key);
            i.tile = t;
            i.tileW = w;
            i.tileH = h;
            return i;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final Host host;
    private final float radius;
    private final int gap;
    private final int rowPadStart;
    private final int rowPadEnd;
    private final int spans;
    private final RecyclerView.RecycledViewPool tilePool = new RecyclerView.RecycledViewPool();
    private final Map<String, Parcelable> rowScroll = new HashMap<>();
    private final Map<String, Integer> rowFocus = new HashMap<>();

    HomeAdapter(Host host, float radiusPx, int gapPx, int rowPadStart, int rowPadEnd, int spans) {
        this.host = host;
        this.radius = radiusPx;
        this.gap = gapPx;
        this.rowPadStart = rowPadStart;
        this.rowPadEnd = rowPadEnd;
        this.spans = spans;
        tilePool.setMaxRecycledViews(0, 24);
        setHasStableIds(true);
    }

    void submit(List<Item> next) {
        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
    }

    Item item(int pos) {
        return items.get(pos);
    }

    int spanSize(int pos) {
        return items.get(pos).type == TILE ? 1 : spans;
    }

    /** Positions up to and including the first row scroll the page back to the top. */
    boolean snapsToTop(int pos) {
        for (int i = 0; i < items.size(); i++) {
            int t = items.get(i).type;
            if (t == ROW || t == TILE) return pos <= i;
        }
        return true;
    }

    private String focusedSection;
    private float titleScale = 1f;

    void setTitleScale(float k) {
        titleScale = k;
    }

    private float titleAlpha(String section) {
        return focusedSection == null || focusedSection.equals(section) ? 1f : 0.5f;
    }

    /**
     * Brightens the title of the row that has focus and dims the others, so
     * it's always clear where you are. Only alpha changes (no layout), and a
     * plain TextView fades without an offscreen layer.
     */
    void setFocusedSection(RecyclerView list, String section) {
        if (section == null || section.equals(focusedSection)) return;
        focusedSection = section;
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            Object tag = v.getTag(R.id.section_tag);
            if (tag instanceof String) {
                v.animate().alpha(titleAlpha((String) tag)).setDuration(160).start();
            }
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id.hashCode();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        switch (type) {
            case HEADER:
                return new Plain(inf.inflate(R.layout.home_header, parent, false));
            case TITLE:
                TextView title = (TextView) inf.inflate(R.layout.home_title, parent, false);
                if (titleScale != 1f) {
                    // Scaled once, when the view is created, so it never compounds.
                    title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, title.getTextSize() * titleScale);
                    ViewGroup.LayoutParams lp = title.getLayoutParams();
                    lp.height = Math.round(lp.height * titleScale);
                    title.setLayoutParams(lp);
                }
                return new Plain(title);
            case ROW:
                return new RowHolder(parent);
            case NOW: {
                android.widget.FrameLayout frame = new android.widget.FrameLayout(parent.getContext());
                frame.setClipChildren(false);
                frame.setClipToPadding(false);
                frame.addView(new NowPlayingCard(parent.getContext(), radius));
                return new Plain(frame);
            }
            default:
                TileView v = new TileView(parent.getContext(), radius);
                // Spacing comes from GridSpacing, so tiles in every column are the same width.
                v.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                wire(v, host);
                return new Plain(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Item item = items.get(position);
        switch (item.type) {
            case HEADER:
                host.bindHeader(holder.itemView);
                break;
            case TITLE:
                TextView title = (TextView) holder.itemView;
                String section = item.id.substring("title:".length());
                title.setText(item.text);
                title.setTag(R.id.section_tag, section);
                title.animate().cancel();
                title.setAlpha(titleAlpha(section));
                break;
            case ROW:
                ((RowHolder) holder).bind(item);
                break;
            case NOW: {
                android.widget.FrameLayout frame = (android.widget.FrameLayout) holder.itemView;
                NowPlayingCard card = (NowPlayingCard) frame.getChildAt(0);
                int padV = Math.round(item.tileH * 0.07f);
                frame.setPadding(rowPadStart, padV + gap / 2, rowPadEnd, padV + gap / 2);
                frame.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        item.tileH + gap + 2 * padV));
                card.setLayoutParams(new android.widget.FrameLayout.LayoutParams(item.tileW, item.tileH));
                host.bindNowPlaying(card);
                break;
            }
            default:
                TileView v = (TileView) holder.itemView;
                v.bind(item.tile);
                host.bindArt(v, item.tile, item.tileW, item.tileH);
                v.syncFocusScale();
                break;
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof RowHolder) ((RowHolder) holder).save();
    }

    static void wire(TileView v, Host host) {
        v.setOnClickListener(x -> {
            Tile t = v.tile();
            if (t == null || t.onClick == null) return;
            if (t.app != null) v.zoomForLaunch();
            Sounds.select();
            t.onClick.run();
        });
        v.setOnLongClickListener(x -> {
            Tile t = v.tile();
            if (t == null || t.onLongClick == null) return false;
            t.onLongClick.run();
            return true;
        });
        v.setOnFocusChangeListener((x, has) -> {
            if (has) host.onTileFocused(v);
        });
    }

    private static final class Plain extends RecyclerView.ViewHolder {
        Plain(View v) {
            super(v);
        }
    }

    private final class RowHolder extends RecyclerView.ViewHolder {
        final RowRecyclerView rv;
        final RowLayoutManager lm;
        final RowAdapter adapter = new RowAdapter();
        String boundId;

        RowHolder(ViewGroup parent) {
            super(new RowRecyclerView(parent.getContext()));
            rv = (RowRecyclerView) itemView;
            lm = new RowLayoutManager(parent.getContext());
            rv.setLayoutManager(lm);
            rv.setRecycledViewPool(tilePool);
            rv.setItemAnimator(null);
            rv.setHasFixedSize(true);
            rv.setClipToPadding(false);
            rv.setClipChildren(false);
            rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            rv.setHorizontalScrollBarEnabled(false);
            rv.setAdapter(adapter);
        }

        void bind(Item item) {
            save();
            boundId = item.id;
            // Room above and below for the 1.1x focus zoom, so nothing is clipped.
            int padV = Math.round(item.tileH * 0.07f);
            rv.setPadding(rowPadStart - gap / 2, padV, rowPadEnd - gap / 2, padV);
            ViewGroup.LayoutParams lp = rv.getLayoutParams();
            int h = item.tileH + gap + 2 * padV; // tile + its margins + zoom room
            if (lp == null) {
                rv.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
            } else if (lp.height != h) {
                lp.height = h;
                rv.setLayoutParams(lp);
            }
            adapter.set(item.tiles, item.tileW, item.tileH);
            Parcelable state = rowScroll.get(item.id);
            if (state != null) lm.onRestoreInstanceState(state);
            else lm.scrollToPosition(0);
            Integer f = rowFocus.get(item.id);
            rv.setLastFocused(f != null ? Math.min(f, item.tiles.size() - 1) : 0);
        }

        void save() {
            if (boundId == null) return;
            Parcelable s = lm.onSaveInstanceState();
            if (s != null) rowScroll.put(boundId, s);
            rowFocus.put(boundId, rv.lastFocused());
        }
    }

    private final class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<Tile> tiles = new ArrayList<>();
        private int w;
        private int h;

        RowAdapter() {
            setHasStableIds(true);
        }

        void set(List<Tile> next, int width, int height) {
            tiles = next;
            w = width;
            h = height;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return tiles.size();
        }

        @Override
        public long getItemId(int position) {
            return tiles.get(position).key.hashCode();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TileView v = new TileView(parent.getContext(), radius);
            wire(v, host);
            return new Plain(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            TileView v = (TileView) holder.itemView;
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) v.getLayoutParams();
            if (lp == null || lp.width != w || lp.height != h) {
                lp = new RecyclerView.LayoutParams(w, h);
                lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
                v.setLayoutParams(lp);
            }
            Tile t = tiles.get(position);
            v.bind(t);
            host.bindArt(v, t, w, h);
            v.syncFocusScale();
        }
    }
}
