package io.github.grimmmyy.lumen;

import android.app.Activity;
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
    private NowPlaying nowPlaying;
    private NowPlaying.State playing;
    private Weather weather;
    private Updater updater;
    private AmbientView ambient;
    private boolean swallowKeyUp;
    private final Runnable enterAmbient = this::enterAmbient;
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
    /** Package → last used, from usage access (empty without it). */
    private Map<String, Long> usage = Collections.emptyMap();
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
    private TextView focusLabel;
    private View focusBar;
    private boolean focusLabelShown;
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
        // A fresh install picks up /sdcard/Lumen/lumen-settings.json if it's there.
        boolean restored = Backup.restoreOnFirstRun(this);
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

        Motion.effects = prefs.option(Prefs.OPT_MOTION);
        Backdrop.style = prefs.backgroundStyle();
        weather = new Weather(this);
        updater = new Updater(this);
        Backdrop.timeOfDay = prefs.option(Prefs.OPT_TIME_TINT);
        Sounds.setEnabled(this, prefs.option(Prefs.OPT_SOUNDS));
        art = LumenApp.art(this, radius);
        nowPlaying = new NowPlaying(this, io);
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
        ambient = new AmbientView(this);
        ambient.setTranslationZ(5f);
        ((ViewGroup) findViewById(android.R.id.content)).addView(ambient,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        focusLabel = findViewById(R.id.focusLabel);
        focusBar = findViewById(R.id.focusBar);
        home.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy != 0) updateFocusLabel();
            }
        });

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

        if (restored) toast("Settings restored from " + Backup.NAME);

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
        updateGreeting();
        // "Opened just now" and friends are time-based: refresh them for the tile in focus.
        View f = getCurrentFocus();
        if (f instanceof TileView) onTileFocused((TileView) f);
        refresh();
        main.postDelayed(markStable, 5_000);
        if (LumenApp.screenJustOn) {
            LumenApp.screenJustOn = false;
            if (prefs.profiles().size() > 1 && prefs.askOnWake()) main.postDelayed(() -> showProfiles(true), 400);
        }
        scheduleAmbient();
        weather.refresh(line -> updateGreeting());
        updater.check(false, () -> {
            if (updater.available() != null) {
                signature = null; // the Lumen tile says an update is ready
                rebuild();
            }
        });
        // First run: show Setup once, so the Home button doesn't stay off unnoticed.
        if (!prefs.setupShown() && !isHomeWatcherEnabled()) main.postDelayed(this::openSetup, 1_500);
        // Art trimmed while away is reloaded in the background, after the first frames.
        main.postDelayed(rewarm, 700);
    }

    private List<HomeAdapter.Item> currentItems = Collections.emptyList();
    private final Runnable rewarm = () -> warmArt(currentItems);

    @Override
    protected void onPause() {
        main.removeCallbacks(enterAmbient);
        exitAmbient(true);
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
        if (Sheet.handleKey(this, event)) return true;
        // Any key wakes Home from ambient mode, and does nothing else.
        if (ambient != null && ambient.isShowing() && event.getAction() == KeyEvent.ACTION_DOWN) {
            exitAmbient(false);
            swallowKeyUp = true;
            scheduleAmbient();
            return true;
        }
        if (swallowKeyUp && event.getAction() == KeyEvent.ACTION_UP) {
            swallowKeyUp = false;
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) scheduleAmbient();
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
    protected void onStart() {
        super.onStart();
        nowPlaying.start(this::onNowPlaying);
        playing = nowPlaying.current();
    }

    @Override
    protected void onStop() {
        nowPlaying.stop();
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
            Map<String, Long> used = Usage.lastUsed(this);
            if (scanned != null) art.prune(scanned);
            main.post(() -> {
                if (isDestroyed()) return;
                if (current != null) apps = current;
                watch = w;
                usage = used;
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
        Set<String> grouped = new HashSet<>();
        for (String g : prefs.groups()) grouped.addAll(prefs.groupApps(g));
        for (String p : recentOrder()) {
            if (recents.size() >= MAX_RECENT_TILES) break;
            if (byPkg.containsKey(p) && !hidden.contains(p) && !favSet.contains(p)) recents.add(p);
        }
        boolean showRecent = prefs.rowEnabled(Prefs.ROW_RECENT);
        boolean showApps = prefs.rowEnabled(Prefs.ROW_APPS);
        boolean showQuick = prefs.rowEnabled(Prefs.ROW_QUICK);

        StringBuilder gsig = new StringBuilder();
        for (String g : prefs.groups()) gsig.append(g).append('=').append(prefs.groupApps(g)).append(';');
        String sig = signature(favs, hidden, recents, showRecent, showApps, showQuick) + gsig + quickStatus()
                + (playing != null && prefs.rowEnabled(Prefs.ROW_NOW));
        if (sig.equals(signature)) return;
        signature = sig;

        View focused = getCurrentFocus();
        String focusKey = focused instanceof TileView && ((TileView) focused).tile() != null
                ? ((TileView) focused).tile().key : null;

        List<HomeAdapter.Item> items = new ArrayList<>();
        items.add(HomeAdapter.Item.header());

        if (playing != null && prefs.rowEnabled(Prefs.ROW_NOW)) {
            items.add(HomeAdapter.Item.title("now", "Now Playing"));
            items.add(HomeAdapter.Item.now(shelfW * 2 + gap, shelfH));
        }

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

        for (String g : prefs.groups()) {
            List<Tile> gt = new ArrayList<>();
            for (String p : prefs.groupApps(g)) {
                AppEntry a = byPkg.get(p);
                if (a != null && !hidden.contains(p)) gt.add(appTile(a, "grp:" + g + ":"));
            }
            if (gt.isEmpty()) continue;
            items.add(HomeAdapter.Item.title("grp:" + g, g));
            items.add(HomeAdapter.Item.row("grp:" + g, gt, smallW, smallH));
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
                if (hidden.contains(a.pkg) || favSet.contains(a.pkg) || grouped.contains(a.pkg)) continue;
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

    /** Recently used first: Lumen's own launches, plus apps opened elsewhere when usage access is on. */
    private List<String> recentOrder() {
        Map<String, Long> when = new HashMap<>();
        for (String p : prefs.recents()) when.put(p, Math.max(1, prefs.lastLaunch(p)));
        for (Map.Entry<String, Long> e : usage.entrySet()) {
            String p = e.getKey();
            if (p.equals(getPackageName()) || p.equals(FIRE_LAUNCHER) || !byPkg.containsKey(p)) continue;
            Long t = when.get(p);
            if (t == null || e.getValue() > t) when.put(p, e.getValue());
        }
        List<String> out = new ArrayList<>(when.keySet());
        Collections.sort(out, (x, y) -> Long.compare(when.get(y), when.get(x)));
        return out;
    }

    /** Connected over Wi-Fi (with signal strength) or Ethernet, or offline. */
    private String networkStatus() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) return "Offline";
            if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext()
                        .getSystemService(WIFI_SERVICE);
                int level = android.net.wifi.WifiManager.calculateSignalLevel(wm.getConnectionInfo().getRssi(), 4);
                return "Wi-Fi  \u00b7  " + new String[]{"weak", "fair", "good", "strong"}[Math.max(0, Math.min(3, level))];
            }
            return "Connected";
        } catch (RuntimeException e) {
            return "Network";
        }
    }

    private String storageStatus() {
        android.os.StatFs fs = new android.os.StatFs(Environment.getDataDirectory().getPath());
        long free = fs.getAvailableBytes();
        return (free >= 1L << 30 ? String.format(java.util.Locale.US, "%.1f GB", free / (double) (1L << 30))
                : (free >> 20) + " MB") + " free";
    }

    private String quickStatus() {
        return networkStatus() + "|" + storageStatus() + "|" + SleepTimer.minutesLeft() + "|"
                + (updater != null ? updater.available() : null);
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
        // New or just updated, and not opened since: a small NEW badge.
        long seen = Math.max(prefs.lastLaunch(a.pkg), usage.containsKey(a.pkg) ? usage.get(a.pkg) : 0L);
        if (System.currentTimeMillis() - a.updated < 3 * 86_400_000L && seen < a.updated) t.badge = "NEW";
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
        q.add(iconTile(textTile("q:wifi", "Network", networkStatus(), 0xFF1F3A5C,
                () -> openSetting(Settings.ACTION_WIFI_SETTINGS)), R.drawable.ic_q_wifi));
        q.add(iconTile(textTile("q:display", "Display & Sound", "Picture, audio", 0xFF33284F,
                () -> openSetting(Settings.ACTION_DISPLAY_SETTINGS)), R.drawable.ic_q_display));
        q.add(iconTile(textTile("q:remotes", "Remotes", "Bluetooth devices", 0xFF1F4A47,
                // Fire OS has no Bluetooth settings action; its Controllers page covers remotes.
                () -> openSetting("android.settings.CONTROLLERS_SETTINGS", Settings.ACTION_BLUETOOTH_SETTINGS)),
                R.drawable.ic_q_remote));
        int left = SleepTimer.minutesLeft();
        q.add(iconTile(textTile("q:sleep", "Sleep timer", left > 0 ? "In " + left + " min" : "Off", 0xFF2B2A4A,
                this::showSleepTimer), R.drawable.ic_q_sleep));
        q.add(iconTile(textTile("q:apps", "Manage apps", storageStatus(), 0xFF4A3A1F,
                () -> openSetting(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)), R.drawable.ic_q_apps));
        q.add(iconTile(textTile("q:settings", "Settings", "All Fire TV settings", 0xFF2A2F3A,
                () -> openSetting(Settings.ACTION_SETTINGS)), R.drawable.ic_q_settings));
        String update = updater != null ? updater.available() : null;
        q.add(iconTile(textTile("q:lumen", "Lumen", update != null ? "Update " + update + " ready" : "Settings, setup",
                0xFF3A2430, this::showLumenSettings),
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
        updateGreeting();
    }

    /** "Good evening", plus the weather when it's turned on. */
    private void updateGreeting() {
        if (greetingView == null) return;
        String w = weather != null ? weather.cached() : null;
        greetingView.setText(w == null ? greeting() : greeting() + "   \u00b7   " + w);
        if (ambient != null && ambient.isShowing()) ambient.setWeather(weatherLineLong());
    }

    private String weatherLineLong() {
        String w = weather.cached();
        return w == null ? null : w + "   \u00b7   " + weather.placeName();
    }

    // ---- Ambient mode ---------------------------------------------------------

    private void scheduleAmbient() {
        main.removeCallbacks(enterAmbient);
        int min = prefs.ambientMinutes();
        if (min > 0 && !isFinishing()) main.postDelayed(enterAmbient, min * 60_000L);
    }

    private void enterAmbient() {
        if (ambient.isShowing() || !hasWindowFocus()) return;
        weather.refresh(line -> updateGreeting());
        Bitmap scene = backdrop.current();
        if (scene == null) scene = ArtGen.render(ArtGen.NONE.equals(Backdrop.style) ? "aurora" : Backdrop.style,
                System.currentTimeMillis() / 86_400_000L);
        ambient.show(scene, weatherLineLong(), nowPlayingLine());
        // Stop drawing the rows (and the backdrop under the opaque scene) once covered.
        main.postDelayed(() -> {
            if (!ambient.isShowing()) return;
            home.setVisibility(View.INVISIBLE);
            findViewById(R.id.backdropA).setVisibility(View.INVISIBLE);
            findViewById(R.id.backdropB).setVisibility(View.INVISIBLE);
        }, 950);
    }

    private void exitAmbient(boolean quick) {
        if (ambient == null || !ambient.isShowing()) return;
        home.setVisibility(View.VISIBLE);
        findViewById(R.id.backdropA).setVisibility(View.VISIBLE);
        findViewById(R.id.backdropB).setVisibility(View.VISIBLE);
        ambient.hide(quick);
    }

    private String nowPlayingLine() {
        NowPlaying.State s = playing;
        if (s == null || !s.playing) return null;
        return s.subtitle != null ? s.title + "  \u2014  " + s.subtitle : s.title;
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

    private String greeting() {
        String g = greetingBase();
        return prefs != null && prefs.profiles().size() > 1 ? g + ", " + prefs.profileName() : g;
    }

    private static String greetingBase() {
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
        Sounds.focus();
        focusLabel.setText(t.title);
        updateFocusLabel();
        heroHint = t.onLongClick != null ? "Select to open   ·   ☰  Options" : "Select to open";
        if (heroTitleView != null) {
            heroTitleView.setText(heroTitle);
            heroSubtitleView.setText(heroSubtitle);
            heroHintView.setText(heroHint);
        }
        adapter.setFocusedSection(home, t.section);
        backdrop.request(t.backdropPkg, t.imageUri, t.color, view.backdropSeed());
    }

    /**
     * Shows the focused tile's name at the top while the info band is scrolled
     * away (deep in the Apps grid), so banner-only tiles are never anonymous.
     */
    private void updateFocusLabel() {
        View header = layout.findViewByPosition(0);
        boolean show = header == null || header.getBottom() < dp(120);
        if (show == focusLabelShown) return;
        focusLabelShown = show;
        focusBar.animate().alpha(show ? 1f : 0f).setDuration(show ? 180 : 120).start();
    }

    // ---- Now Playing ----------------------------------------------------------

    private void onNowPlaying(NowPlaying.State s) {
        boolean wasShown = playing != null;
        playing = s;
        if (ambient.isShowing()) ambient.setNowPlaying(nowPlayingLine());
        if ((s != null) != wasShown) {
            rebuild(); // the card appears or goes
            return;
        }
        View v = home.findViewWithTag("nowplaying");
        if (v instanceof NowPlayingCard) {
            NowPlayingCard card = (NowPlayingCard) v;
            card.setState(s);
            if (card.isFocused()) onCardFocused(card);
        }
    }

    @Override
    public void bindNowPlaying(NowPlayingCard card) {
        card.setState(playing);
        card.setOnClickListener(x -> openNowPlaying());
        card.setOnLongClickListener(x -> {
            showNowPlayingMenu();
            return true;
        });
        card.setOnFocusChangeListener((x, has) -> {
            if (has) onCardFocused(card);
        });
    }

    private void onCardFocused(NowPlayingCard card) {
        NowPlaying.State s = card.state();
        if (s == null) return;
        heroTitle = s.title;
        heroSubtitle = (s.playing ? "Playing on " : "Paused on ") + s.appLabel;
        Sounds.focus();
        focusLabel.setText(s.title);
        updateFocusLabel();
        heroHint = "Select to open   ·   ⏯ Play/Pause   ·   ☰  More";
        if (heroTitleView != null) {
            heroTitleView.setText(heroTitle);
            heroSubtitleView.setText(heroSubtitle);
            heroHintView.setText(heroHint);
        }
        adapter.setFocusedSection(home, "now");
        backdrop.requestArt(s.pkg + "|" + s.title, card.tint(), s.art);
    }

    private void openNowPlaying() {
        NowPlaying.State s = playing;
        if (s == null) return;
        android.app.PendingIntent pi = s.controller.getSessionActivity();
        if (pi != null) {
            try {
                pi.send();
                return;
            } catch (android.app.PendingIntent.CanceledException ignored) {
                // fall back to the app's launcher entry
            }
        }
        AppEntry a = byPkg.get(s.pkg);
        if (a != null) launchApp(a);
        else {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(s.pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(s.pkg);
            if (i != null) launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void showNowPlayingMenu() {
        NowPlaying.State s = playing;
        if (s == null) return;
        Sheet.with(this, s.title).caption("Now Playing")
                .subtitle((s.subtitle != null ? s.subtitle + "\n" : "") + (s.playing ? "Playing on " : "Paused on ") + s.appLabel)
                .item(s.playing ? "Pause" : "Play", nowPlaying::playPause)
                .item("Next", nowPlaying::next)
                .item("Previous", nowPlaying::previous)
                .item("Open " + s.appLabel, this::openNowPlaying)
                .show();
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
        Sheet sheet = Sheet.with(this, a.label).caption("App").subtitle(appDetails(a, null));
        if (favorite) {
            sheet.item("Move left", () -> moveFavorite(a.pkg, -1));
            sheet.item("Move right", () -> moveFavorite(a.pkg, 1));
            sheet.item("Remove from Top Shelf", () -> {
                List<String> f = prefs.favorites();
                f.remove(a.pkg);
                prefs.setFavorites(f);
                rebuild();
            });
        } else {
            sheet.item("Add to Top Shelf", () -> {
                List<String> f = prefs.favorites();
                if (!f.contains(a.pkg)) f.add(a.pkg);
                prefs.setFavorites(f);
                rebuild();
            });
        }
        String group = prefs.groupOf(a.pkg);
        if (group == null) {
            sheet.item("Add to a group", () -> chooseGroup(a));
        } else {
            sheet.item("Remove from \u201c" + group + "\u201d", () -> {
                List<String> g = prefs.groupApps(group);
                g.remove(a.pkg);
                if (g.isEmpty()) prefs.deleteGroup(group);
                else prefs.setGroupApps(group, g);
                rebuild();
            });
            if (prefix.startsWith("grp:")) {
                sheet.item("Move left in group", () -> moveInGroup(group, a.pkg, -1));
                sheet.item("Move right in group", () -> moveInGroup(group, a.pkg, 1));
                sheet.item("Rename \u201c" + group + "\u201d", () -> askName("Rename group", group, name -> {
                    prefs.renameGroup(group, name);
                    rebuild();
                }));
            }
        }
        if ("rec:".equals(prefix)) {
            sheet.item("Remove from Recent", () -> {
                prefs.forgetRecent(a.pkg);
                rebuild();
            });
        }
        sheet.item("Hide", () -> {
            Set<String> h = prefs.hidden();
            h.add(a.pkg);
            prefs.setHidden(h);
            rebuild();
        });
        sheet.item("App info", () -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", a.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        sheet.show();
        io.execute(() -> {
            String sizes = Usage.sizes(this, a.pkg);
            if (sizes != null) main.post(() -> sheet.updateSubtitle(appDetails(a, sizes)));
        });
    }

    /** Name, then version and last update, then storage use when usage access is on. */
    private CharSequence appDetails(AppEntry a, String sizes) {
        String version = "";
        try {
            version = "Version " + getPackageManager().getPackageInfo(a.pkg, 0).versionName + "  \u00b7  ";
        } catch (PackageManager.NameNotFoundException ignored) {
            // just removed
        }
        String updated = "Updated " + android.text.format.DateUtils.getRelativeTimeSpanString(a.updated,
                System.currentTimeMillis(), android.text.format.DateUtils.DAY_IN_MILLIS);
        return version + updated + (sizes != null ? "\n" + sizes : "");
    }

    private void chooseGroup(AppEntry a) {
        Sheet sheet = Sheet.with(this, "Add to a group").caption(a.label)
                .subtitle("Groups are your own rows on Home. Apps in a group leave the Apps grid.");
        for (String g : prefs.groups()) sheet.item(g, () -> addToGroup(g, a.pkg));
        sheet.item("New group\u2026", () -> askName("New group", "", name -> addToGroup(name, a.pkg)));
        sheet.show();
    }

    private void addToGroup(String group, String pkg) {
        List<String> g = prefs.groupApps(group);
        if (!g.contains(pkg)) g.add(pkg);
        prefs.setGroupApps(group, g);
        rebuild();
    }

    private void moveInGroup(String group, String pkg, int delta) {
        List<String> g = prefs.groupApps(group);
        int i = g.indexOf(pkg), j = i + delta;
        if (i < 0 || j < 0 || j >= g.size()) return;
        g.set(i, g.get(j));
        g.set(j, pkg);
        prefs.setGroupApps(group, g);
        rebuild();
    }

    private void askName(String title, String current, java.util.function.Consumer<String> done) {
        Sheet sheet = Sheet.with(this, title).subtitle("Press Select on the field to type.");
        android.widget.EditText name = sheet.input("e.g. Kids, Music, Games", current);
        sheet.button("Save", false, () -> {
            String n = name.getText().toString().trim().replace('\n', ' ').replace(',', ' ');
            if (!n.isEmpty()) done.accept(n);
        });
        sheet.button("Cancel", false, null);
        sheet.show();
    }

    private void showSleepTimer() {
        final int[] minutes = {0, 15, 30, 60, 90};
        String[] labels = {"Off", "In 15 minutes", "In 30 minutes", "In 1 hour", "In 1 hour 30 minutes"};
        int left = SleepTimer.minutesLeft();
        Sheet sheet = Sheet.with(this, "Sleep timer").caption("Quick")
                .subtitle(left > 0 ? "The TV sleeps in " + left + " min. Whatever is playing is paused first."
                        : "Pauses whatever is playing, then puts the TV to sleep.");
        for (int i = 0; i < minutes.length; i++) {
            final int m = minutes[i];
            sheet.choice(labels[i], m == 0 ? left == 0 : false, () -> {
                SleepTimer.set(this, m);
                if (m > 0 && !isHomeWatcherEnabled()) {
                    toast("The TV will sleep only if the Home button service is on (Lumen > Setup)");
                }
                signature = null;
                rebuild();
            });
        }
        sheet.item("Sleep now", () -> {
            if (!SleepTimer.sleepNow(this)) toast("Needs the Home button service (Lumen > Setup)");
        });
        sheet.show();
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
        String bg = ArtGen.LABELS[Math.max(0, java.util.Arrays.asList(ArtGen.STYLES).indexOf(prefs.backgroundStyle()))];
        Sheet.with(this, "Settings").caption("Lumen " + BuildConfigHelper.versionName(this))
                .item("Setup and health", watcherOn ? null : "Home button off", this::openSetup)
                .item("Updates", updater.available() != null ? updater.available() + " ready" : "Up to date",
                        this::showUpdates)
                .item("Profile", prefs.profileName(), () -> showProfiles(false))
                .item("Layout", this::showLayoutOptions)
                .item("Background", bg, this::showBackgroundStyles)
                .item("Ambient mode", prefs.ambientMinutes() == 0 ? "Off" : prefs.ambientMinutes() + " min",
                        this::showAmbientOptions)
                .item("Weather", weather.enabled() ? weather.placeName() : "Off", this::showWeatherOptions)
                .item("Hidden apps", String.valueOf(prefs.hidden().size()), this::showHiddenApps)
                .item("Wallpapers", this::showWallpaperHelp)
                .item("Reload wallpapers and apps", () -> {
                    images.clear();
                    backdrop.invalidate();
                    AppCatalog.markDirty();
                    signature = null;
                    refresh();
                })
                .item("Back up settings", () -> io.execute(() -> {
                    try {
                        String where = Backup.export(this);
                        main.post(() -> toast("Saved to " + where));
                    } catch (Exception e) {
                        main.post(() -> toast("Couldn't save: " + e.getMessage()));
                    }
                }))
                .item("Restore settings", this::restoreSettings)
                .item("Reset Top Shelf", () -> {
                    prefs.resetFavorites();
                    rebuild();
                })
                .item("Open Fire TV home", this::openFireHome)
                .show();
    }

    /**
     * Profiles: switch, add, rename, remove. With {@code wake}, it's the short
     * "Who's watching?" list shown when the TV wakes.
     */
    private void showProfiles(boolean wake) {
        List<Prefs.Profile> list = prefs.profiles();
        int current = prefs.currentProfile();
        Sheet sheet = Sheet.with(this, wake ? "Who's watching?" : "Profiles")
                .subtitle(wake ? null : "Each profile has its own Top Shelf, hidden apps, Recent row and background.");
        for (Prefs.Profile p : list) sheet.choice(p.name, p.id == current, () -> switchProfile(p.id));
        if (!wake) {
            sheet.item("Add a profile", () -> askName("New profile", "", name -> switchProfile(prefs.addProfile(name))));
            sheet.item("Rename \u201c" + prefs.profileName() + "\u201d", () -> askName("Rename profile", prefs.profileName(), name -> {
                prefs.renameProfile(prefs.currentProfile(), name);
                updateGreeting();
            }));
            if (current != 0) {
                sheet.item("Remove \u201c" + prefs.profileName() + "\u201d", () -> {
                    prefs.removeProfile(current);
                    switchProfile(0);
                });
            }
            if (list.size() > 1) sheet.toggle("Ask who's watching when the TV wakes", prefs.askOnWake(), prefs::setAskOnWake);
        }
        sheet.show();
    }

    private void switchProfile(int id) {
        prefs.setCurrentProfile(id);
        Backdrop.style = prefs.backgroundStyle();
        backdrop.invalidate();
        signature = null;
        apps = AppCatalog.quick(this) != null ? AppCatalog.quick(this) : apps;
        home.scrollToPosition(0);
        rebuild();
        updateGreeting();
        // Start the new profile on its Top Shelf (not on whatever tile the menu was
        // opened from), once the rebuilt rows have been laid out.
        home.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                home.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                home.post(MainActivity.this::focusShelf);
            }
        });
        home.requestLayout();
    }

    private void showBackgroundStyles() {
        Sheet sheet = Sheet.with(this, "Background")
                .subtitle("Your own wallpapers (Settings > Wallpapers) always come first.");
        String current = prefs.backgroundStyle();
        for (int i = 0; i < ArtGen.STYLES.length; i++) {
            final String style = ArtGen.STYLES[i];
            sheet.choice(ArtGen.LABELS[i], style.equals(current), () -> {
                prefs.setBackgroundStyle(style);
                Backdrop.style = style;
                backdrop.invalidate();
                View f = getCurrentFocus();
                if (f instanceof TileView) onTileFocused((TileView) f);
            });
        }
        sheet.show();
    }

    private void showAmbientOptions() {
        final int[] minutes = {0, 1, 3, 5, 10};
        String[] labels = {"Off", "After 1 minute", "After 3 minutes", "After 5 minutes", "After 10 minutes"};
        Sheet sheet = Sheet.with(this, "Ambient mode")
                .subtitle("When Home is left idle, the rows fade away behind a large clock, the weather and what's "
                        + "playing. Any button brings Home back.");
        for (int i = 0; i < minutes.length; i++) {
            final int m = minutes[i];
            sheet.choice(labels[i], m == prefs.ambientMinutes(), () -> {
                prefs.setAmbientMinutes(m);
                scheduleAmbient();
            });
        }
        sheet.show();
    }

    private void showWeatherOptions() {
        Sheet sheet = Sheet.with(this, "Weather")
                .subtitle("Shows the temperature next to the greeting and in ambient mode, from Open-Meteo. Only the "
                        + "city (to find it) and its coordinates are sent; no account.");
        android.widget.EditText city = sheet.input("City, e.g. Seattle",
                weather.enabled() ? weather.placeName().split(",")[0] : "");
        sheet.button("Save", false, () -> {
            String q = city.getText().toString().trim();
            if (q.isEmpty()) return;
            weather.setCity(q, place -> {
                toast(place == null ? "Couldn't find that city" : "Weather for " + place.name);
                weather.refresh(line -> updateGreeting());
            });
        });
        sheet.toggle("Temperature in \u00b0F", weather.fahrenheit(), on -> {
            weather.setFahrenheit(on);
            weather.refresh(line -> updateGreeting());
        });
        if (weather.enabled()) sheet.item("Turn weather off", () -> {
            weather.clear();
            updateGreeting();
        });
        sheet.show();
    }

    /** Updates: version, release notes, install, check now, automatic checks. */
    private void showUpdates() {
        String v = updater.available();
        long checked = updater.lastChecked();
        String when = checked == 0 ? "not checked yet" : "checked " + android.text.format.DateUtils
                .getRelativeTimeSpanString(checked, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
                .toString().toLowerCase(java.util.Locale.getDefault());
        Sheet sheet = Sheet.with(this, v != null ? "Lumen " + v + " is ready" : "Lumen is up to date")
                .caption("Updates")
                .subtitle("You have " + updater.current() + "  \u00b7  " + when);
        if (v != null) {
            if (!updater.notes().isEmpty()) sheet.text(updater.notes());
            if (updater.canInstall()) {
                sheet.button("Install " + v, true, () -> updater.install((msg, done) -> {
                    sheet.updateSubtitle(msg);
                    if (done) toast(msg);
                }));
            } else {
                sheet.text("Lumen needs permission to install updates. Fire TV hides this setting; from a computer:");
                sheet.code("adb shell appops set " + getPackageName() + " REQUEST_INSTALL_PACKAGES allow");
            }
        }
        sheet.button("Check now", false, () -> {
            toast("Checking for updates\u2026");
            updater.check(true, () -> {
                signature = null;
                rebuild();
                showUpdates();
            });
        });
        sheet.toggle("Check automatically (once a day)", updater.autoCheck(), updater::setAutoCheck);
        sheet.show();
    }

    private void restoreSettings() {
        java.io.File f = Backup.find();
        if (f == null) {
            toast("No " + Backup.NAME + " in /sdcard/Lumen or Download");
            return;
        }
        Sheet.with(this, "Restore settings").subtitle("Replaces your Top Shelf, groups, profiles and options with "
                + "the ones saved in " + f.getAbsolutePath() + ".")
                .button("Restore", false, () -> {
                    try {
                        Backup.restore(this, f);
                        toast("Settings restored");
                        recreate();
                    } catch (Exception e) {
                        toast("Couldn't restore: " + e.getMessage());
                    }
                })
                .button("Cancel", false, null)
                .show();
    }

    private void showLayoutOptions() {
        final boolean wasLarge = prefs.largeTiles();
        Sheet.with(this, "Layout").subtitle("Changes apply when you close this panel.")
                .toggle("Now Playing card", prefs.rowEnabled(Prefs.ROW_NOW), on -> prefs.setRowEnabled(Prefs.ROW_NOW, on))
                .toggle("Recent row", prefs.rowEnabled(Prefs.ROW_RECENT), on -> prefs.setRowEnabled(Prefs.ROW_RECENT, on))
                .toggle("Apps grid", prefs.rowEnabled(Prefs.ROW_APPS), on -> prefs.setRowEnabled(Prefs.ROW_APPS, on))
                .toggle("Quick row", prefs.rowEnabled(Prefs.ROW_QUICK), on -> prefs.setRowEnabled(Prefs.ROW_QUICK, on))
                .toggle("Larger tiles and text", wasLarge, prefs::setLargeTiles)
                .toggle("Motion effects", prefs.option(Prefs.OPT_MOTION), on -> {
                    prefs.setOption(Prefs.OPT_MOTION, on);
                    Motion.effects = on;
                })
                .toggle("Adapt colors to time of day", prefs.option(Prefs.OPT_TIME_TINT), on -> {
                    prefs.setOption(Prefs.OPT_TIME_TINT, on);
                    Backdrop.timeOfDay = on;
                    backdrop.invalidate();
                })
                .toggle("Sounds", prefs.option(Prefs.OPT_SOUNDS), on -> {
                    prefs.setOption(Prefs.OPT_SOUNDS, on);
                    Sounds.setEnabled(this, on);
                })
                .onClose(() -> {
                    // Tile sizes are fixed at creation, so a size change rebuilds the screen.
                    if (prefs.largeTiles() != wasLarge) recreate();
                    else rebuild();
                })
                .show();
    }

    private void openSetup() {
        prefs.setSetupShown();
        launch(new Intent(this, SetupActivity.class));
    }

    private void showHiddenApps() {
        List<String> pkgs = new ArrayList<>(prefs.hidden());
        if (pkgs.isEmpty()) {
            toast("No hidden apps");
            return;
        }
        Sheet sheet = Sheet.with(this, "Hidden apps").subtitle("Select an app to show it again.");
        for (String p : pkgs) {
            AppEntry a = byPkg.get(p);
            sheet.item(a != null ? a.label : p, () -> {
                Set<String> h = prefs.hidden();
                h.remove(p);
                prefs.setHidden(h);
                rebuild();
            });
        }
        sheet.show();
    }

    private void showWallpaperHelp() {
        boolean canRead = checkSelfPermission(storagePermission()) == PackageManager.PERMISSION_GRANTED;
        String path = backdrop.folder().getAbsolutePath();
        Sheet sheet = Sheet.with(this, "Wallpapers")
                .subtitle("Without wallpapers, the background is a soft blur of the focused app, or the style you "
                        + "pick in Background.")
                .text("Copy images to\n" + path + "\n\n"
                        + "default.jpg \u2014 shown for every app\n"
                        + "<package>.jpg \u2014 shown when that app is focused, e.g. com.netflix.ninja.jpg\n\n"
                        + "PNG and WebP work too; 1920\u00d71080 is ideal. Then choose \u201cReload wallpapers and apps\u201d.");
        if (!canRead) {
            sheet.text("Lumen needs permission to read photos to show them.");
            sheet.button("Allow", false, () -> requestPermissions(new String[]{storagePermission()}, REQ_STORAGE));
        }
        sheet.button("OK", false, null);
        sheet.show();
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
