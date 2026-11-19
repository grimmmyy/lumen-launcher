package io.github.grimmmyy.lumen;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** User choices: the Top Shelf order, hidden apps, and the Fire TV home bypass window. */
final class Prefs {
    private static final String FILE = "lumen";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_SEEDED = "seeded";
    private static final String KEY_BYPASS_UNTIL = "bypass_until";
    private static final String KEY_ASKED_EPG = "asked_epg";

    /** Apps put on the Top Shelf the first time Lumen runs, if installed. */
    private static final String[] DEFAULT_FAVORITES = {
            "com.netflix.ninja",
            "com.amazon.firebat",
            "com.disney.disneyplus",
            "com.amazon.firetv.youtube",
            "com.google.android.youtube.tv",
            "com.hbo.hbonow",
            "com.wbd.stream",
            "com.hulu.plus",
            "com.plexapp.android",
            "com.tubitv.ott",
    };

    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    List<String> favorites() {
        String raw = sp.getString(KEY_FAVORITES, "");
        List<String> out = new ArrayList<>();
        if (!TextUtils.isEmpty(raw)) out.addAll(Arrays.asList(raw.split(",")));
        return out;
    }

    void setFavorites(List<String> pkgs) {
        sp.edit().putString(KEY_FAVORITES, TextUtils.join(",", pkgs)).apply();
    }

    /** First run only: fill the Top Shelf with whichever default apps are installed. */
    void seedFavoritesIfNeeded(Set<String> installed) {
        if (sp.getBoolean(KEY_SEEDED, false)) return;
        List<String> seed = new ArrayList<>();
        for (String p : DEFAULT_FAVORITES) {
            if (installed.contains(p)) seed.add(p);
        }
        sp.edit().putString(KEY_FAVORITES, TextUtils.join(",", seed)).putBoolean(KEY_SEEDED, true).apply();
    }

    Set<String> hidden() {
        return new HashSet<>(sp.getStringSet(KEY_HIDDEN, new HashSet<>()));
    }

    void setHidden(Set<String> pkgs) {
        sp.edit().putStringSet(KEY_HIDDEN, new HashSet<>(pkgs)).apply();
    }

    void resetFavorites() {
        sp.edit().remove(KEY_FAVORITES).putBoolean(KEY_SEEDED, false).apply();
    }

    /** Lets the user open the Fire TV home on purpose without Lumen jumping back in. */
    void bypassFireHomeFor(long millis) {
        sp.edit().putLong(KEY_BYPASS_UNTIL, System.currentTimeMillis() + millis).apply();
    }

    boolean isFireHomeBypassed() {
        return System.currentTimeMillis() < sp.getLong(KEY_BYPASS_UNTIL, 0);
    }

    // ---- Layout options -----------------------------------------------------

    static final String ROW_RECENT = "row_recent";
    static final String ROW_QUICK = "row_quick";
    static final String ROW_APPS = "row_apps";
    private static final String KEY_RECENTS = "recents";
    private static final int MAX_RECENTS = 12;

    private static final String KEY_LARGE = "large_tiles";

    boolean largeTiles() {
        return sp.getBoolean(KEY_LARGE, false);
    }

    void setLargeTiles(boolean on) {
        sp.edit().putBoolean(KEY_LARGE, on).apply();
    }

    boolean rowEnabled(String row) {
        return sp.getBoolean(row, true);
    }

    void setRowEnabled(String row, boolean on) {
        sp.edit().putBoolean(row, on).apply();
    }

    /** Most recently launched first. Tracked by Lumen itself: no usage-access permission needed. */
    List<String> recents() {
        String raw = sp.getString(KEY_RECENTS, "");
        List<String> out = new ArrayList<>();
        if (!TextUtils.isEmpty(raw)) out.addAll(Arrays.asList(raw.split(",")));
        return out;
    }

    void noteLaunch(String pkg) {
        List<String> r = recents();
        r.remove(pkg);
        r.add(0, pkg);
        while (r.size() > MAX_RECENTS) r.remove(r.size() - 1);
        sp.edit().putString(KEY_RECENTS, TextUtils.join(",", r))
                .putLong("launched:" + pkg, System.currentTimeMillis())
                .apply();
    }

    /** When Lumen last opened this app, or 0 if never. */
    long lastLaunch(String pkg) {
        return sp.getLong("launched:" + pkg, 0);
    }

    void forgetRecent(String pkg) {
        List<String> r = recents();
        if (r.remove(pkg)) sp.edit().putString(KEY_RECENTS, TextUtils.join(",", r)).apply();
    }

    // ---- Crash guard --------------------------------------------------------
    // These use commit() rather than apply(): a crash kills the process before
    // an async write lands, so the record has to be on disk before we die.

    private static final String KEY_CRASHES = "crash_times";
    private static final String KEY_REDIRECTS = "redirect_times";
    private static final String KEY_SAFE_MODE = "safe_mode";
    private static final int KEEP = 6;

    void recordCrash() {
        sp.edit().putString(KEY_CRASHES, append(sp.getString(KEY_CRASHES, ""))).commit();
    }

    int crashesWithin(long windowMs) {
        return countWithin(sp.getString(KEY_CRASHES, ""), windowMs);
    }

    void recordRedirect() {
        sp.edit().putString(KEY_REDIRECTS, append(sp.getString(KEY_REDIRECTS, ""))).commit();
    }

    int redirectsWithin(long windowMs) {
        return countWithin(sp.getString(KEY_REDIRECTS, ""), windowMs);
    }

    boolean isSafeMode() {
        return sp.getBoolean(KEY_SAFE_MODE, false);
    }

    void setSafeMode(boolean on) {
        SharedPreferences.Editor e = sp.edit().putBoolean(KEY_SAFE_MODE, on);
        if (!on) e.remove(KEY_CRASHES).remove(KEY_REDIRECTS);
        e.commit();
    }

    private static String append(String raw) {
        List<String> parts = new ArrayList<>();
        if (!TextUtils.isEmpty(raw)) parts.addAll(Arrays.asList(raw.split(",")));
        parts.add(String.valueOf(System.currentTimeMillis()));
        while (parts.size() > KEEP) parts.remove(0);
        return TextUtils.join(",", parts);
    }

    private static int countWithin(String raw, long windowMs) {
        if (TextUtils.isEmpty(raw)) return 0;
        long cutoff = System.currentTimeMillis() - windowMs;
        int n = 0;
        for (String s : raw.split(",")) {
            try {
                if (Long.parseLong(s) >= cutoff) n++;
            } catch (NumberFormatException ignored) {
                // skip a corrupt entry
            }
        }
        return n;
    }

    boolean askedForEpg() {
        return sp.getBoolean(KEY_ASKED_EPG, false);
    }

    void setAskedForEpg() {
        sp.edit().putBoolean(KEY_ASKED_EPG, true).apply();
    }
}
