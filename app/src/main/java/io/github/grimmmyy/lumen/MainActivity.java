package io.github.grimmmyy.lumen;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements TileAdapter.Host {
    private static final String EPG_PERMISSION = "com.android.providers.tv.permission.READ_EPG_DATA";
    private static final String FIRE_LAUNCHER = "com.amazon.tv.launcher";
    private static final int REQ_EPG = 7;
    /** Rows are added after the header and spacer that live in the layout. */
    private static final int STATIC_CHILDREN = 2;
    private static final int GRID_COLUMNS = 5;

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private ImageLoader images;
    private Backdrop backdrop;
    private NestedScrollView scroll;
    private LinearLayout rows;
    private TextView focusTitle;
    private TextView focusSubtitle;

    private final Map<String, AppEntry> appsByPkg = new HashMap<>();
    private String lastSignature;
    private int loadGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        images = new ImageLoader(this, io, main);
        scroll = findViewById(R.id.scroll);
        rows = findViewById(R.id.rows);
        focusTitle = findViewById(R.id.focusTitle);
        focusSubtitle = findViewById(R.id.focusSubtitle);
        backdrop = new Backdrop(findViewById(R.id.backdropA), findViewById(R.id.backdropB),
                main, images, getExternalFilesDir("wallpapers"), getResources());

        if (checkSelfPermission(EPG_PERMISSION) != PackageManager.PERMISSION_GRANTED && !prefs.askedForEpg()) {
            prefs.setAskedForEpg();
            requestPermissions(new String[]{EPG_PERMISSION}, REQ_EPG);
        }
    }

    /** Lumen has stayed on screen long enough to count as working: reset the crash guard. */
    private final Runnable markStable = () -> {
        boolean wasSafe = prefs.isSafeMode();
        // Clears crash and redirect history too. Old crashes must not count once
        // Lumen has shown it works, even if safe mode never switched on (the
        // watcher lives in Lumen's process, so it can miss events while we crash).
        prefs.setSafeMode(false);
        android.util.Log.i("Lumen", "stable: guard cleared (was safe=" + wasSafe + ")");
        if (wasSafe) toast("Home button opens Lumen again");
    };

    @Override
    protected void onResume() {
        super.onResume();
        reload(false);
        main.postDelayed(markStable, 5_000);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(markStable);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while Lumen is already showing: go back to the top.
        goToTop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_EPG) reload(true);
    }

    @Override
    public void onBackPressed() {
        // A launcher has nowhere to go back to. Use Back to return to the top instead.
        goToTop();
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
        if (level >= TRIM_MEMORY_BACKGROUND) images.clear();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ---- Loading ------------------------------------------------------------

    private void reload(boolean force) {
        final int gen = ++loadGeneration;
        io.execute(() -> {
            List<AppEntry> apps = AppRepository.load(this);
            List<WatchNext.Item> watch = WatchNext.load(this);
            main.post(() -> {
                if (gen != loadGeneration || isDestroyed()) return;
                bind(apps, watch, force);
            });
        });
    }

    private void bind(List<AppEntry> apps, List<WatchNext.Item> watch, boolean force) {
        appsByPkg.clear();
        Set<String> installed = new HashSet<>();
        for (AppEntry a : apps) {
            appsByPkg.put(a.pkg, a);
            installed.add(a.pkg);
        }
        prefs.seedFavoritesIfNeeded(installed);

        List<String> favs = new ArrayList<>();
        for (String p : prefs.favorites()) if (installed.contains(p)) favs.add(p);
        Set<String> hidden = prefs.hidden();

        String signature = signature(apps, favs, hidden, watch);
        if (!force && signature.equals(lastSignature)) return; // nothing changed: no flicker
        lastSignature = signature;

        View focused = getCurrentFocus();
        String focusKey = focused != null && focused.getTag() instanceof String ? (String) focused.getTag() : null;

        rows.removeViews(STATIC_CHILDREN, rows.getChildCount() - STATIC_CHILDREN);

        // Row 0: Top Shelf
        List<Tile> shelf = new ArrayList<>();
        for (String p : favs) {
            if (hidden.contains(p)) continue;
            shelf.add(appTile(appsByPkg.get(p), 0, true));
        }
        if (shelf.isEmpty()) {
            Tile hint = systemTile("shelf:hint", 0, "Add apps here",
                    "Long-press any app (or press Menu) and choose Add to Top Shelf");
            hint.onClick = () -> toast("Long-press an app and choose Add to Top Shelf");
            shelf.add(hint);
        }
        addRow("Top Shelf", shelf, 224, 126, false);

        // Row 1: Continue Watching (only if any app publishes it)
        if (!watch.isEmpty()) {
            List<Tile> cw = new ArrayList<>();
            int i = 0;
            for (WatchNext.Item w : watch) cw.add(watchTile(w, i++));
            addRow("Continue Watching", cw, 224, 126, false);
        }

        // Row 2: every other app, as a grid
        List<Tile> rest = new ArrayList<>();
        Set<String> favSet = new HashSet<>(favs);
        for (AppEntry a : apps) {
            if (hidden.contains(a.pkg) || favSet.contains(a.pkg)) continue;
            rest.add(appTile(a, 2, false));
        }
        if (!rest.isEmpty()) addRow("Apps", rest, 152, 86, true);

        // Row 3: system shortcuts
        List<Tile> sys = new ArrayList<>();
        Tile ls = systemTile("sys:lumen", 3, "Lumen settings", "Home button, hidden apps, wallpapers");
        ls.onClick = this::showLumenSettings;
        sys.add(ls);
        Tile fs = systemTile("sys:fire", 3, "Fire TV settings", "Network, display, remotes, updates");
        fs.onClick = () -> launch(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        sys.add(fs);
        addRow("System", sys, 152, 86, false);

        main.post(() -> restoreFocus(focusKey));
    }

    private static String signature(List<AppEntry> apps, List<String> favs, Set<String> hidden,
                                    List<WatchNext.Item> watch) {
        StringBuilder sb = new StringBuilder();
        for (AppEntry a : apps) sb.append(a.pkg).append(',');
        sb.append('|').append(TextUtils.join(",", favs)).append('|');
        List<String> h = new ArrayList<>(hidden);
        java.util.Collections.sort(h);
        sb.append(TextUtils.join(",", h)).append('|');
        for (WatchNext.Item w : watch) sb.append(w.title).append(w.progress).append(',');
        return sb.toString();
    }

    private void addRow(String title, List<Tile> tiles, int wDp, int hDp, boolean grid) {
        View row = LayoutInflater.from(this).inflate(R.layout.row_section, rows, false);
        ((TextView) row.findViewById(R.id.rowTitle)).setText(title);
        RecyclerView rv = row.findViewById(R.id.rowList);
        if (grid) {
            rv.setLayoutManager(new GridLayoutManager(this, GRID_COLUMNS));
            rv.setNestedScrollingEnabled(false);
        } else {
            rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        }
        rv.setItemAnimator(null);
        rv.setAdapter(new TileAdapter(tiles, dp(wDp), dp(hDp), dp(12), this));
        rows.addView(row);
    }

    private void restoreFocus(String key) {
        View target = key != null ? rows.findViewWithTag(key) : null;
        if (target == null) target = firstTile();
        if (target != null) target.requestFocus();
    }

    private View firstTile() {
        if (rows.getChildCount() <= STATIC_CHILDREN) return null;
        RecyclerView rv = rows.getChildAt(STATIC_CHILDREN).findViewById(R.id.rowList);
        return rv.getChildCount() > 0 ? rv.getChildAt(0) : null;
    }

    private void goToTop() {
        scroll.smoothScrollTo(0, 0);
        if (rows.getChildCount() > STATIC_CHILDREN) {
            RecyclerView rv = rows.getChildAt(STATIC_CHILDREN).findViewById(R.id.rowList);
            rv.scrollToPosition(0);
            main.post(() -> {
                View first = firstTile();
                if (first != null) first.requestFocus();
            });
        }
    }

    // ---- Tiles --------------------------------------------------------------

    private Tile appTile(AppEntry a, int row, boolean favorite) {
        Tile t = new Tile();
        t.key = (favorite ? "fav:" : "app:") + a.pkg;
        t.row = row;
        t.title = a.label;
        t.banner = a.banner;
        t.icon = a.icon;
        t.color = a.color;
        t.backdropPkg = a.pkg;
        t.onClick = () -> launch(a.launch);
        t.onLongClick = () -> showAppMenu(a, favorite);
        return t;
    }

    private Tile watchTile(WatchNext.Item w, int index) {
        AppEntry owner = w.pkg != null ? appsByPkg.get(w.pkg) : null;
        Tile t = new Tile();
        t.key = "cw:" + index + ":" + w.title;
        t.row = 1;
        t.title = w.title;
        t.subtitle = owner != null
                ? (w.subtitle != null ? w.subtitle + "  ·  " + owner.label : owner.label)
                : w.subtitle;
        t.imageUri = w.posterUri;
        t.icon = owner != null ? owner.icon : null;
        t.color = owner != null ? owner.color : 0xFF1B2333;
        t.progress = w.progress;
        t.backdropPkg = w.pkg;
        t.onClick = () -> {
            Intent i = w.toIntent();
            if (i == null && owner != null) i = owner.launch;
            if (i != null) launch(i);
        };
        return t;
    }

    private Tile systemTile(String key, int row, String title, String subtitle) {
        Tile t = new Tile();
        t.key = key;
        t.row = row;
        t.title = title;
        t.subtitle = subtitle;
        t.icon = getDrawable(R.drawable.ic_lumen);
        t.color = 0xFF1B2333;
        return t;
    }

    @Override
    public void onTileFocused(Tile t) {
        focusTitle.setText(t.title);
        focusSubtitle.setText(t.subtitle != null ? t.subtitle : "");
        if (t.row == 0) scroll.smoothScrollTo(0, 0);
        backdrop.request(t.backdropPkg, t.color, t.imageUri);
    }

    @Override
    public ImageLoader images() {
        return images;
    }

    // ---- Menus --------------------------------------------------------------

    private void showAppMenu(AppEntry a, boolean favorite) {
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
                reload(true);
            });
        } else {
            labels.add("Add to Top Shelf");
            actions.add(() -> {
                List<String> f = prefs.favorites();
                if (!f.contains(a.pkg)) f.add(a.pkg);
                prefs.setFavorites(f);
                reload(true);
            });
        }
        labels.add("Hide");
        actions.add(() -> {
            Set<String> h = prefs.hidden();
            h.add(a.pkg);
            prefs.setHidden(h);
            reload(true);
        });
        labels.add("App info");
        actions.add(() -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", a.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
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
        reload(true);
    }

    private void showLumenSettings() {
        boolean watcherOn = isHomeWatcherEnabled();
        String[] items = {
                "Home button: " + (watcherOn ? "On" : "Off — how to turn on"),
                "Hidden apps (" + prefs.hidden().size() + ")",
                "Wallpapers",
                "Reload wallpapers and apps",
                "Reset Top Shelf",
                "Open Fire TV home",
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Lumen " + BuildConfigHelper.versionName(this))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: showHomeButtonHelp(watcherOn); break;
                        case 1: showHiddenApps(); break;
                        case 2: showWallpaperHelp(); break;
                        case 3:
                            images.clear();
                            backdrop.invalidate();
                            reload(true);
                            break;
                        case 4:
                            prefs.resetFavorites();
                            reload(true);
                            break;
                        case 5: openFireHome(); break;
                        default: break;
                    }
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
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Home button")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setNeutralButton("Accessibility settings", (d, w) ->
                        launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
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
            AppEntry a = appsByPkg.get(pkgs.get(i));
            names[i] = a != null ? a.label : pkgs.get(i);
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Choose an app to show again")
                .setItems(names, (d, which) -> {
                    Set<String> h = prefs.hidden();
                    h.remove(pkgs.get(which));
                    prefs.setHidden(h);
                    reload(true);
                })
                .show();
    }

    private void showWallpaperHelp() {
        String path = backdrop.folder() != null ? backdrop.folder().getAbsolutePath() : "(unavailable)";
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Wallpapers")
                .setMessage("Put images in:\n" + path + "\n\n"
                        + "default.jpg  — shown for every app\n"
                        + "<package>.jpg  — shown when that app is focused, e.g. com.netflix.ninja.jpg\n\n"
                        + "PNG and WebP work too. 1920×1080 is ideal. Then choose "
                        + "\"Reload wallpapers and apps\".")
                .setPositiveButton("OK", null)
                .show();
    }

    private void openFireHome() {
        prefs.bypassFireHomeFor(60_000);
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(FIRE_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launch(i);
    }

    private boolean isHomeWatcherEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(this, HomeWatcherService.class);
        for (String s : enabled.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (me.equals(c)) return true;
        }
        return false;
    }

    // ---- Helpers ------------------------------------------------------------

    private void launch(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast("Can't open that");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
