package io.github.grimmmyy.lumen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

public class MainActivity extends Activity implements HomeAdapter.Host {
    private static final String TAG = "Lumen";
    private static final String EPG_PERMISSION = "com.android.providers.tv.permission.READ_EPG_DATA";
    private static final String FIRE_LAUNCHER = "com.amazon.tv.launcher";
    private static final int REQ_EPG = 7;
    private static final int REQ_STORAGE = 8;
    private static final float KEYLINE = 0.36f;
    private static final int MAX_RECENT_TILES = 6;

    /** Shared with the art cache and kept for the life of the process (see LumenApp). */
    private final ExecutorService io = LumenApp.io();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private ImageLoader images;
    private ArtCache art;
    private Backdrop backdrop;
    private RecyclerView home;
    private HomeLayoutManager layout;
    private HomeAdapter adapter;

    private int spanCount;
    private float radius;
    private int gap;
    private int side;
    private int shelfW;
    private int shelfH;
    private int smallW;
    private int smallH;
    private int gridW;
    private int gridH;

    private List<AppEntry> apps = Collections.emptyList();
    private List<WatchNext.Item> watch = Collections.emptyList();
    private final Map<String, AppEntry> byPkg = new HashMap<>();
    private String signature;
    private String firstShelfKey;
    private int shelfPosition = -1;
    private boolean firstFocusDone;
    private boolean reportedDrawn;

    private View headerView;
    private TextView heroTitleView;
    private TextView heroSubtitleView;
    private TextView heroHintView;
    private TextView greetingView;
    private String heroTitle = "";
    private String heroSubtitle = "";
    private String heroHint = "";

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            AppCatalog.markDirty();
            refresh();
        }
    };

    /** Lumen has stayed on screen long enough to count as working: reset the crash guard. */
    private final Runnable markStable = () -> {
        boolean wasSafe = prefs.isSafeMode();
        // A synchronous disk write (tens of ms on a Fire TV Stick): not on the
        // thread that draws, 5 s after Home is exactly when people are scrolling.
        io.execute(() -> prefs.setSafeMode(false));
        if (wasSafe) toast("Home button opens Lumen again");
    };

    // ---- Lifecycle ----------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shownAt = SystemClock.uptimeMillis();
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        images = new ImageLoader(this, io, main);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        // "Larger tiles": fewer, bigger targets and bigger text, for viewing from
        // further away or with low vision. Tile text scales with tile size.
        boolean large = prefs.largeTiles();
        spanCount = large ? 4 : 5;
        radius = dp(large ? 12 : 10);
        gap = dp(large ? 20 : 16);
        side = dp(48);
        shelfW = dp(large ? 272 : 224);
        shelfH = Math.round(shelfW * 9f / 16f);
        smallW = dp(large ? 204 : 168);
        smallH = Math.round(smallW * 9f / 16f);
        gridW = GridSpacing.tileWidth(dm.widthPixels, side, gap, spanCount);
        gridH = Math.round(gridW * 9f / 16f);

        art = LumenApp.art(this, radius);
        backdrop = new Backdrop(findViewById(R.id.backdropA), findViewById(R.id.backdropB), io, main, images,
                new File(userDir(), "wallpapers"),
                // Once the backdrop is opaque the window background is pure overdraw.
                () -> getWindow().setBackgroundDrawable(null));

        home = findViewById(R.id.home);
        adapter = new HomeAdapter(this, radius, gap, side, side, spanCount);
        if (large) adapter.setTitleScale(1.2f);
        layout = new HomeLayoutManager(this, spanCount, KEYLINE, pos -> adapter.snapsToTop(pos));
        GridLayoutManager.SpanSizeLookup spans = new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.spanSize(position);
            }
        };
        spans.setSpanIndexCacheEnabled(true);
        layout.setSpanSizeLookup(spans);
        home.setLayoutManager(layout);
        home.setAdapter(adapter);
        home.addItemDecoration(new GridSpacing(side, gap));
        home.setItemAnimator(null);
        home.setHasFixedSize(true);
        home.setItemViewCacheSize(8);
        // The backdrop images use Z 0/1 to order their crossfade; keep the list above both.
        // (No background means no outline, so this draws no shadow.)
        home.setZ(2f);

        // Draw immediately from the in-memory list or the disk snapshot.
        List<AppEntry> quick = AppCatalog.quick(this);
        if (quick != null) {
            apps = quick;
            rebuild();
        }
        refresh();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);

        if (checkSelfPermission(EPG_PERMISSION) != PackageManager.PERMISSION_GRANTED && !prefs.askedForEpg()) {
            prefs.setAskedForEpg();
            requestPermissions(new String[]{EPG_PERMISSION}, REQ_EPG);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        shownAt = SystemClock.uptimeMillis();
        rebuild(); // cheap no-op unless recents or settings changed while away
        if (greetingView != null) greetingView.setText(greeting());
        // "Opened just now" and friends are time-based: refresh them for the tile in focus.
        View f = getCurrentFocus();
        if (f instanceof TileView) onTileFocused((TileView) f);
        refresh();
        main.postDelayed(markStable, 5_000);
        // Art trimmed while away is reloaded in the background, after the first frames.
        main.postDelayed(rewarm, 700);
    }

    private List<HomeAdapter.Item> currentItems = Collections.emptyList();
    private final Runnable rewarm = () -> warmArt(currentItems);

    @Override
    protected void onPause() {
        main.removeCallbacks(markStable);
        main.removeCallbacks(rewarm);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        goToTop(); // Home pressed while Lumen is showing
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_EPG) refresh();
        if (requestCode == REQ_STORAGE) {
            backdrop.invalidate();
            signature = null;
            rebuild();
        }
    }

    @Override
    public void onBackPressed() {
        goToTop(); // a launcher has nowhere to go back to
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        Motion.onKey(event);
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && f.performLongClick()) return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) releaseMemory();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Not left to onTrimMemory: while the Home-button service is bound, Android
        // keeps Lumen at foreground priority and never sends TRIM_MEMORY_UI_HIDDEN.
        releaseMemory();
    }

    /**
     * Another app is in front: hand memory back to it while you watch. Tiles
     * still on screen keep their art (their views hold it), so returning Home
     * shows no placeholders; the rest reloads from disk in the background.
     */
    private void releaseMemory() {
        backdrop.trim();
        images.clear();
        art.trim(visibleArt());
        // Bitmap memory is only returned once the Java objects are collected,
        // and Lumen allocates so little on the Java heap that a GC may not
        // come for minutes. Nobody is looking at Lumen now, so ask for one.
        io.execute(() -> Runtime.getRuntime().gc());
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        main.removeCallbacksAndMessages(null);
        // The worker threads are shared and live on (see LumenApp); tasks already
        // queued check isDestroyed() before touching this screen.
        super.onDestroy();
    }

    /** Art on screen now, to keep in memory while Lumen is hidden. */
    private java.util.Set<Bitmap> visibleArt() {
        java.util.Set<Bitmap> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        collectArt(home, out);
        return out;
    }

    private static void collectArt(View v, java.util.Set<Bitmap> out) {
        if (v instanceof TileView) {
            Bitmap b = ((TileView) v).art();
            if (b != null) out.add(b);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectArt(g.getChildAt(i), out);
        }
    }

    // ---- Loading ------------------------------------------------------------

    /**
     * A rescan reads every app's label from its APK, and opening another app's
     * resources takes a process-wide lock the main thread also needs to draw:
     * on a Fire TV Stick 4K Max the main thread waited up to 133 ms for it. So
     * when a list is already on screen (memory or snapshot), the rescan waits
     * until the screen has been up for a moment.
     */
    private static final long SCAN_DELAY_MS = 2_000;
    private long shownAt;
    private final Runnable deferredRefresh = this::refresh;

    /** Background: rescan only if packages changed, reload Continue Watching, then rebuild. */
    private boolean stressRead;

    private void refresh() {
        final boolean readStress = !stressRead;
        stressRead = true;
        final boolean scanNow = !AppCatalog.hasList() || SystemClock.uptimeMillis() - shownAt >= SCAN_DELAY_MS;
        io.execute(() -> {
            if (readStress) readStressSetting();
            boolean scan = AppCatalog.needsScan(this);
            if (scan && !scanNow) {
                scan = false;
                main.post(() -> {
                    main.removeCallbacks(deferredRefresh);
                    if (!isDestroyed()) main.postDelayed(deferredRefresh, SCAN_DELAY_MS);
                });
            }
            List<AppEntry> scanned = scan ? AppCatalog.scan(this) : null;
            List<AppEntry> current = scanned != null ? scanned : AppCatalog.quick(this);
            List<WatchNext.Item> w = checkSelfPermission(EPG_PERMISSION) == PackageManager.PERMISSION_GRANTED
                    ? WatchNext.load(this) : Collections.emptyList();
            if (scanned != null) art.prune(scanned);
            main.post(() -> {
                if (isDestroyed()) return;
                if (current != null) apps = current;
                watch = w;
                rebuild();
            });
        });
    }

    /** Rebuilds the page from current state. Does nothing if nothing visible changed. */
    private void rebuild() {
        byPkg.clear();
        for (AppEntry a : apps) byPkg.put(a.pkg, a);
        if (!apps.isEmpty()) prefs.seedFavoritesIfNeeded(byPkg.keySet());

        Set<String> hidden = prefs.hidden();
        List<String> favs = new ArrayList<>();
        for (String p : prefs.favorites()) if (byPkg.containsKey(p) && !hidden.contains(p)) favs.add(p);
        Set<String> favSet = new HashSet<>(favs);
        List<String> recents = new ArrayList<>();
        for (String p : prefs.recents()) {
            if (recents.size() >= MAX_RECENT_TILES) break;
            if (byPkg.containsKey(p) && !hidden.contains(p) && !favSet.contains(p)) recents.add(p);
        }
        boolean showRecent = prefs.rowEnabled(Prefs.ROW_RECENT);
        boolean showApps = prefs.rowEnabled(Prefs.ROW_APPS);
        boolean showQuick = prefs.rowEnabled(Prefs.ROW_QUICK);

        String sig = signature(favs, hidden, recents, showRecent, showApps, showQuick);
        if (sig.equals(signature)) return;
        signature = sig;

        View focused = getCurrentFocus();
        String focusKey = focused instanceof TileView && ((TileView) focused).tile() != null
                ? ((TileView) focused).tile().key : null;

        List<HomeAdapter.Item> items = new ArrayList<>();
        items.add(HomeAdapter.Item.header());

        List<Tile> shelf = new ArrayList<>();
        for (String p : favs) shelf.add(appTile(byPkg.get(p), "fav:"));
        if (shelf.isEmpty()) shelf.add(hintTile());
        if (!showQuick) shelf.add(lumenSettingsTile()); // settings must stay reachable
        firstShelfKey = shelf.get(0).key;
        items.add(HomeAdapter.Item.title("shelf", "Top Shelf"));
        shelfPosition = items.size();
        items.add(HomeAdapter.Item.row("shelf", shelf, shelfW, shelfH));

        if (!watch.isEmpty()) {
            List<Tile> cw = new ArrayList<>();
            for (int i = 0; i < watch.size(); i++) cw.add(watchTile(watch.get(i), i));
            items.add(HomeAdapter.Item.title("cw", "Continue Watching"));
            items.add(HomeAdapter.Item.row("cw", cw, shelfW, shelfH));
        }

        if (showRecent && !recents.isEmpty()) {
            List<Tile> rec = new ArrayList<>();
            for (String p : recents) rec.add(appTile(byPkg.get(p), "rec:"));
            items.add(HomeAdapter.Item.title("recent", "Recent"));
            items.add(HomeAdapter.Item.row("recent", rec, smallW, smallH));
        }

        if (showApps) {
            List<HomeAdapter.Item> grid = new ArrayList<>();
            for (AppEntry a : apps) {
                if (hidden.contains(a.pkg) || favSet.contains(a.pkg)) continue;
                grid.add(HomeAdapter.Item.tile(appTile(a, "app:"), gridW, gridH));
            }
            int copies = stressCopies();
            if (copies > 0 && !grid.isEmpty()) {
                // Developer stress test: repeat the grid to measure scrolling a large library.
                int n = grid.size();
                for (int c = 1; c <= copies; c++) {
                    for (int i = 0; i < n; i++) {
                        Tile src = grid.get(i).tile;
                        Tile t = appTile(src.app, "app" + c + ":");
                        grid.add(HomeAdapter.Item.tile(t, gridW, gridH));
                    }
                }
            }
            if (!grid.isEmpty()) {
                items.add(HomeAdapter.Item.title("apps", "Apps"));
                items.addAll(grid);
            }
        }

        if (showQuick) {
            items.add(HomeAdapter.Item.title("quick", "Quick"));
            items.add(HomeAdapter.Item.row("quick", quickTiles(), smallW, smallH));
        }

        for (HomeAdapter.Item it : items) {
            if (it.type == HomeAdapter.ROW) {
                String section = it.id.substring("row:".length());
                for (Tile t : it.tiles) t.section = section;
            } else if (it.type == HomeAdapter.TILE) {
                it.tile.section = "apps";
            }
        }

        adapter.submit(items);
        currentItems = items;
        home.post(() -> {
            restoreFocus(focusKey);
            if (!reportedDrawn && !apps.isEmpty()) {
                reportedDrawn = true;
                reportFullyDrawn();
            }
            warmArt(items);
        });
    }

    private String signature(List<String> favs, Set<String> hidden, List<String> recents,
                             boolean r, boolean a, boolean q) {
        StringBuilder sb = new StringBuilder(apps.size() * 40);
        for (AppEntry e : apps) sb.append(e.pkg).append(':').append(e.updated).append(',');
        sb.append('|').append(favs).append('|');
        List<String> h = new ArrayList<>(hidden);
        Collections.sort(h);
        sb.append(h).append('|').append(recents).append('|').append(r).append(a).append(q).append('|');
        sb.append(stressCopies()).append('|');
        for (WatchNext.Item w : watch) sb.append(w.title).append(w.progress).append(',');
        return sb.toString();
    }

    /**
     * The user-visible folder for wallpapers (and the benchmark stress file).
     * /sdcard/Lumen, because Android 11+ blocks adb from writing into an app's
     * own Android/data folder. Reading it needs the optional storage permission.
     */
    @SuppressWarnings("deprecation")
    static File userDir() {
        return new File(Environment.getExternalStorageDirectory(), "Lumen");
    }

    private String storagePermission() {
        return Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_IMAGES"
                : "android.permission.READ_EXTERNAL_STORAGE";
    }

    private volatile int stress;

    /**
     * Benchmarking only: `adb shell setprop debug.lumen.stress 8` repeats the
     * Apps grid 8 extra times, to measure scrolling a large library. Read once,
     * in the background, at startup; 0 (off) in normal use.
     */
    private void readStressSetting() {
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"getprop", "debug.lumen.stress"});
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                stress = line == null || line.trim().isEmpty() ? 0
                        : Math.max(0, Math.min(50, Integer.parseInt(line.trim())));
            }
        } catch (Exception e) {
            stress = 0;
        }
    }

    private int stressCopies() {
        return stress;
    }

    /** Pre-renders art for tiles that are not on screen yet, so scrolling never shows a placeholder. */
    private void warmArt(List<HomeAdapter.Item> items) {
        for (HomeAdapter.Item it : items) {
            if (it.type == HomeAdapter.TILE && it.tile.app != null) {
                art.load(it.tile.app, it.tileW, it.tileH, b -> { });
            } else if (it.type == HomeAdapter.ROW) {
                for (Tile t : it.tiles) if (t.app != null) art.load(t.app, it.tileW, it.tileH, b -> { });
            }
        }
    }

    private void restoreFocus(String key) {
        View target = key != null ? home.findViewWithTag(key) : null;
        if (target != null) {
            target.requestFocus();
            return;
        }
        if (!firstFocusDone || getCurrentFocus() == null) {
            firstFocusDone = focusShelf();
        }
    }

    /** Focuses the Top Shelf tile you were last on (or the first one). */
    private boolean focusShelf() {
        if (shelfPosition < 0) return false;
        View row = layout.findViewByPosition(shelfPosition);
        if (row instanceof RowRecyclerView) {
            RowRecyclerView rv = (RowRecyclerView) row;
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(rv.lastFocused());
            if (vh == null) vh = rv.findViewHolderForAdapterPosition(0);
            if (vh != null) return vh.itemView.requestFocus();
        }
        View first = firstShelfKey != null ? home.findViewWithTag(firstShelfKey) : null;
        return first != null && first.requestFocus();
    }

    private void goToTop() {
        home.smoothScrollToPosition(0);
        home.post(this::focusShelf);
    }

    // ---- Tiles --------------------------------------------------------------

    private Tile appTile(AppEntry a, String prefix) {
        Tile t = new Tile();
        t.key = prefix + a.pkg;
        t.title = a.label;
        t.app = a;
        t.color = art.color(a);
        t.backdropPkg = a.pkg;
        t.onClick = () -> launchApp(a);
        t.onLongClick = () -> showAppMenu(a, prefix);
        return t;
    }

    private Tile watchTile(WatchNext.Item w, int index) {
        AppEntry owner = w.pkg != null ? byPkg.get(w.pkg) : null;
        Tile t = new Tile();
        t.key = "cw:" + index + ":" + w.title;
        t.title = w.title;
        t.subtitle = owner != null
                ? (w.subtitle != null ? w.subtitle + "  ·  " + owner.label : owner.label)
                : w.subtitle;
        t.imageUri = w.posterUri;
        t.color = owner != null ? art.color(owner) : 0xFF1B2333;
        t.progress = w.progress;
        t.backdropPkg = w.pkg;
        t.onClick = () -> {
            Intent i = w.toIntent();
            if (i == null && owner != null) i = owner.launchIntent();
            if (i != null) launch(i);
        };
        return t;
    }

    private Tile textTile(String key, String title, String caption, int color, Runnable onClick) {
        Tile t = new Tile();
        t.key = key;
        t.title = title;
        t.caption = caption;
        t.subtitle = caption;
        t.color = color;
        t.onClick = onClick;
        return t;
    }

    private Tile hintTile() {
        return iconTile(textTile("shelf:hint", "Add apps here", "Long-press any app, or press ☰", 0xFF1E2A40,
                () -> toast("Long-press an app (or press Menu) and choose Add to Top Shelf")), R.drawable.ic_q_add);
    }

    private Tile lumenSettingsTile() {
        return iconTile(textTile("shelf:settings", "Lumen", "Settings", 0xFF2A2F3A, this::showLumenSettings),
                R.drawable.ic_q_lumen);
    }

    private Tile iconTile(Tile t, int iconRes) {
        t.icon = getDrawable(iconRes);
        return t;
    }

    private List<Tile> quickTiles() {
        List<Tile> q = new ArrayList<>();
        q.add(iconTile(textTile("q:wifi", "Wi-Fi", "Network", 0xFF1F3A5C,
                () -> openSetting(Settings.ACTION_WIFI_SETTINGS)), R.drawable.ic_q_wifi));
        q.add(iconTile(textTile("q:display", "Display & Sound", "Picture, audio", 0xFF33284F,
                () -> openSetting(Settings.ACTION_DISPLAY_SETTINGS)), R.drawable.ic_q_display));
        q.add(iconTile(textTile("q:remotes", "Remotes", "Bluetooth devices", 0xFF1F4A47,
                // Fire OS has no Bluetooth settings action; its Controllers page covers remotes.
                () -> openSetting("android.settings.CONTROLLERS_SETTINGS", Settings.ACTION_BLUETOOTH_SETTINGS)),
                R.drawable.ic_q_remote));
        q.add(iconTile(textTile("q:apps", "Manage apps", "Storage, uninstall", 0xFF4A3A1F,
                () -> openSetting(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)), R.drawable.ic_q_apps));
        q.add(iconTile(textTile("q:settings", "Settings", "All Fire TV settings", 0xFF2A2F3A,
                () -> openSetting(Settings.ACTION_SETTINGS)), R.drawable.ic_q_settings));
        q.add(iconTile(textTile("q:lumen", "Lumen", "Layout, Home button", 0xFF3A2430, this::showLumenSettings),
                R.drawable.ic_q_lumen));
        return q;
    }

    // ---- HomeAdapter.Host ---------------------------------------------------

    @Override
    public void bindHeader(View header) {
        if (header != headerView) {
            headerView = header;
            heroTitleView = header.findViewById(R.id.heroTitle);
            heroSubtitleView = header.findViewById(R.id.heroMeta);
            heroHintView = header.findViewById(R.id.heroHint);
            greetingView = header.findViewById(R.id.greeting);
            if (prefs.largeTiles()) scaleHeader(header, 1.2f);
        }
        heroTitleView.setText(heroTitle);
        heroSubtitleView.setText(heroSubtitle);
        heroHintView.setText(heroHint);
        greetingView.setText(greeting());
    }

    /** "Larger tiles and text": grow the header's text and its fixed line heights together. */
    private static void scaleHeader(View header, float k) {
        ViewGroup.LayoutParams root = header.getLayoutParams();
        if (root != null && root.height > 0) {
            root.height = Math.round(root.height * k);
            header.setLayoutParams(root);
        }
        ViewGroup g = (ViewGroup) header;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof TextView) {
                TextView tv = (TextView) c;
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.getTextSize() * k);
                ViewGroup.LayoutParams lp = tv.getLayoutParams();
                if (lp.height > 0) lp.height = Math.round(lp.height * k);
                if (lp.width > 0) lp.width = Math.round(lp.width * k);
                tv.setLayoutParams(lp);
            }
        }
    }

    private static String greeting() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (h >= 5 && h < 12) return "Good morning";
        if (h >= 12 && h < 17) return "Good afternoon";
        if (h >= 17 && h < 22) return "Good evening";
        return "Good night";
    }

    /** "Opened 2 hours ago" for apps Lumen has launched; the tile's caption otherwise. */
    private String metaFor(Tile t) {
        if (t.app != null) {
            long when = prefs.lastLaunch(t.app.pkg);
            if (when > 0) {
                long now = System.currentTimeMillis();
                if (now - when < 60_000) return "Opened just now";
                return "Opened " + android.text.format.DateUtils.getRelativeTimeSpanString(
                        when, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
                        .toLowerCase(java.util.Locale.getDefault());
            }
            return "";
        }
        return t.subtitle != null ? t.subtitle : "";
    }

    @Override
    public void bindArt(TileView view, Tile t, int w, int h) {
        if (t.app != null) {
            Bitmap hit = art.peek(t.app, w, h);
            if (hit != null) {
                view.setArt(hit, false);
                return;
            }
            art.load(t.app, w, h, bmp -> {
                if (bmp == null || view.tile() != t) return;
                view.setArt(bmp, false);
                if (view.isFocused()) onTileFocused(view); // backdrop can now use the art
            });
        } else if (t.imageUri != null) {
            images.load(t.imageUri, w, bmp -> {
                if (bmp != null && view.tile() == t) view.setArt(bmp, true);
            });
        }
    }

    @Override
    public void onTileFocused(TileView view) {
        Tile t = view.tile();
        if (t == null) return;
        heroTitle = t.title;
        heroSubtitle = metaFor(t);
        heroHint = t.onLongClick != null ? "Select to open   ·   ☰  Options" : "Select to open";
        if (heroTitleView != null) {
            heroTitleView.setText(heroTitle);
            heroSubtitleView.setText(heroSubtitle);
            heroHintView.setText(heroHint);
        }
        adapter.setFocusedSection(home, t.section);
        backdrop.request(t.backdropPkg, t.imageUri, t.color, view.backdropSeed());
    }

    // ---- Actions ------------------------------------------------------------

    private void launchApp(AppEntry a) {
        prefs.noteLaunch(a.pkg);
        launch(a.launchIntent());
    }

    /**
     * Opens the first settings page that exists and that Lumen may open, else
     * the main Settings page. (Fire OS reserves some, such as Display & Sounds,
     * for Amazon's own apps, and lacks others, such as Bluetooth.)
     */
    private void openSetting(String... actions) {
        for (String action : actions) {
            try {
                startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.i(TAG, action + " unavailable: " + e.getClass().getSimpleName());
            }
        }
        launch(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void showAppMenu(AppEntry a, String prefix) {
        boolean favorite = "fav:".equals(prefix);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (favorite) {
            labels.add("Move left");
            actions.add(() -> moveFavorite(a.pkg, -1));
            labels.add("Move right");
            actions.add(() -> moveFavorite(a.pkg, 1));
            labels.add("Remove from Top Shelf");
            actions.add(() -> {
                List<String> f = prefs.favorites();
                f.remove(a.pkg);
                prefs.setFavorites(f);
                rebuild();
            });
        } else {
            labels.add("Add to Top Shelf");
            actions.add(() -> {
                List<String> f = prefs.favorites();
                if (!f.contains(a.pkg)) f.add(a.pkg);
                prefs.setFavorites(f);
                rebuild();
            });
        }
        if ("rec:".equals(prefix)) {
            labels.add("Remove from Recent");
            actions.add(() -> {
                prefs.forgetRecent(a.pkg);
                rebuild();
            });
        }
        labels.add("Hide");
        actions.add(() -> {
            Set<String> h = prefs.hidden();
            h.add(a.pkg);
            prefs.setHidden(h);
            rebuild();
        });
        labels.add("App info");
        actions.add(() -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", a.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));

        dialog().setTitle(a.label)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void moveFavorite(String pkg, int delta) {
        List<String> f = prefs.favorites();
        int i = f.indexOf(pkg);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= f.size()) return;
        f.set(i, f.get(j));
        f.set(j, pkg);
        prefs.setFavorites(f);
        rebuild();
    }

    private void showLumenSettings() {
        boolean watcherOn = isHomeWatcherEnabled();
        String[] items = {
                "Home button: " + (watcherOn ? "On" : "Off — how to turn on"),
                "Layout",
                "Hidden apps (" + prefs.hidden().size() + ")",
                "Wallpapers",
                "Reload wallpapers and apps",
                "Reset Top Shelf",
                "Open Fire TV home",
        };
        dialog().setTitle("Lumen " + BuildConfigHelper.versionName(this))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: showHomeButtonHelp(watcherOn); break;
                        case 1: showLayoutOptions(); break;
                        case 2: showHiddenApps(); break;
                        case 3: showWallpaperHelp(); break;
                        case 4:
                            images.clear();
                            backdrop.invalidate();
                            AppCatalog.markDirty();
                            signature = null;
                            refresh();
                            break;
                        case 5:
                            prefs.resetFavorites();
                            rebuild();
                            break;
                        case 6: openFireHome(); break;
                        default: break;
                    }
                })
                .show();
    }

    private void showLayoutOptions() {
        final String[] keys = {Prefs.ROW_RECENT, Prefs.ROW_APPS, Prefs.ROW_QUICK};
        String[] labels = {"Recent row", "Apps grid", "Quick controls row", "Larger tiles and text"};
        boolean[] checked = new boolean[labels.length];
        for (int i = 0; i < keys.length; i++) checked[i] = prefs.rowEnabled(keys[i]);
        final boolean wasLarge = prefs.largeTiles();
        checked[3] = wasLarge;
        dialog().setTitle("Home screen layout")
                .setMultiChoiceItems(labels, checked, (d, which, on) -> {
                    if (which < keys.length) prefs.setRowEnabled(keys[which], on);
                    else prefs.setLargeTiles(on);
                })
                .setPositiveButton("Done", null)
                .setOnDismissListener(d -> {
                    // Tile sizes are fixed at creation, so a size change rebuilds the screen.
                    if (prefs.largeTiles() != wasLarge) recreate();
                    else rebuild();
                })
                .show();
    }

    private void showHomeButtonHelp(boolean on) {
        String component = getPackageName() + "/" + HomeWatcherService.class.getName();
        String msg = on
                ? "The Home button opens Lumen. To turn it off, run:\n\n"
                  + "adb shell settings put secure enabled_accessibility_services \"\""
                : "Fire TV hides the setting for this, so turn it on from a computer with ADB:\n\n"
                  + "adb shell settings put secure enabled_accessibility_services " + component + "\n"
                  + "adb shell settings put secure accessibility_enabled 1";
        dialog().setTitle("Home button")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setNeutralButton("Accessibility settings", (d, w) ->
                        openSetting(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                .show();
    }

    private void showHiddenApps() {
        List<String> pkgs = new ArrayList<>(prefs.hidden());
        if (pkgs.isEmpty()) {
            toast("No hidden apps");
            return;
        }
        String[] names = new String[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) {
            AppEntry a = byPkg.get(pkgs.get(i));
            names[i] = a != null ? a.label : pkgs.get(i);
        }
        dialog().setTitle("Choose an app to show again")
                .setItems(names, (d, which) -> {
                    Set<String> h = prefs.hidden();
                    h.remove(pkgs.get(which));
                    prefs.setHidden(h);
                    rebuild();
                })
                .show();
    }

    private void showWallpaperHelp() {
        boolean canRead = checkSelfPermission(storagePermission()) == PackageManager.PERMISSION_GRANTED;
        String path = backdrop.folder().getAbsolutePath();
        AlertDialog.Builder b = dialog().setTitle("Wallpapers")
                .setMessage("Copy images to:\n" + path + "\n\n"
                        + "default.jpg  — shown for every app\n"
                        + "<package>.jpg  — shown when that app is focused, e.g. com.netflix.ninja.jpg\n\n"
                        + "PNG and WebP work too. 1920×1080 is ideal. Then choose "
                        + "\"Reload wallpapers and apps\".\n\n"
                        + "Without wallpapers, the background is a soft blur of the focused app."
                        + (canRead ? "" : "\n\nLumen needs permission to read photos to show them."))
                .setPositiveButton("OK", null);
        if (!canRead) {
            b.setNeutralButton("Allow", (d, w) -> requestPermissions(new String[]{storagePermission()}, REQ_STORAGE));
        }
        b.show();
    }

    private void openFireHome() {
        prefs.bypassFireHomeFor(60_000);
        launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(FIRE_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private boolean isHomeWatcherEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(this, HomeWatcherService.class);
        for (String s : enabled.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(s))) return true;
        }
        return false;
    }

    // ---- Helpers ------------------------------------------------------------

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private void launch(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "launch failed: " + e);
            toast("Can't open that");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
