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
        String raw = sp.getString(pk(KEY_FAVORITES), "");
        List<String> out = new ArrayList<>();
        if (!TextUtils.isEmpty(raw)) out.addAll(Arrays.asList(raw.split(",")));
        return out;
    }

    void setFavorites(List<String> pkgs) {
        sp.edit().putString(pk(KEY_FAVORITES), TextUtils.join(",", pkgs)).apply();
    }

    /** First run only: fill the Top Shelf with whichever default apps are installed. */
    void seedFavoritesIfNeeded(Set<String> installed) {
        if (sp.getBoolean(pk(KEY_SEEDED), false)) return;
        List<String> seed = new ArrayList<>();
        for (String p : DEFAULT_FAVORITES) {
            if (installed.contains(p)) seed.add(p);
        }
        sp.edit().putString(pk(KEY_FAVORITES), TextUtils.join(",", seed)).putBoolean(pk(KEY_SEEDED), true).apply();
    }

    Set<String> hidden() {
        return new HashSet<>(sp.getStringSet(pk(KEY_HIDDEN), new HashSet<>()));
    }

    void setHidden(Set<String> pkgs) {
        sp.edit().putStringSet(pk(KEY_HIDDEN), new HashSet<>(pkgs)).apply();
    }

    void resetFavorites() {
        sp.edit().remove(pk(KEY_FAVORITES)).putBoolean(pk(KEY_SEEDED), false).apply();
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
    static final String ROW_NOW = "row_now";
    private static final String KEY_RECENTS = "recents";
    private static final int MAX_RECENTS = 12;

    private static final String KEY_LARGE = "large_tiles";

    private static final String KEY_SETUP_SHOWN = "setup_shown";

    boolean setupShown() {
        return sp.getBoolean(KEY_SETUP_SHOWN, false);
    }

    void setSetupShown() {
        sp.edit().putBoolean(KEY_SETUP_SHOWN, true).apply();
    }

    static final String OPT_MOTION = "opt_motion";
    static final String OPT_SOUNDS = "opt_sounds";
    static final String OPT_TIME_TINT = "opt_time_tint";

    boolean option(String key) {
        return sp.getBoolean(key, !OPT_SOUNDS.equals(key)); // everything on by default, except sounds
    }

    void setOption(String key, boolean on) {
        sp.edit().putBoolean(key, on).apply();
    }

    // ---- Groups: user-made rows. "groups" is the ordered list of names; each
    // group's apps are stored under "group:" + name.

    List<String> groups() {
        String raw = sp.getString("groups", "");
        List<String> out = new ArrayList<>();
        if (!TextUtils.isEmpty(raw)) out.addAll(Arrays.asList(raw.split("\n")));
        return out;
    }

    List<String> groupApps(String name) {
        String raw = sp.getString("group:" + name, "");
        List<String> out = new ArrayList<>();
        if (!TextUtils.isEmpty(raw)) out.addAll(Arrays.asList(raw.split(",")));
        return out;
    }

    void setGroupApps(String name, List<String> pkgs) {
        List<String> g = groups();
        if (!g.contains(name)) g.add(name);
        sp.edit().putString("groups", TextUtils.join("\n", g)).putString("group:" + name, TextUtils.join(",", pkgs)).apply();
    }

    void deleteGroup(String name) {
        List<String> g = groups();
        g.remove(name);
        sp.edit().putString("groups", TextUtils.join("\n", g)).remove("group:" + name).apply();
    }

    void renameGroup(String from, String to) {
        List<String> g = groups();
        int i = g.indexOf(from);
        if (i < 0 || g.contains(to)) return;
        List<String> apps = groupApps(from);
        g.set(i, to);
        sp.edit().putString("groups", TextUtils.join("\n", g)).remove("group:" + from)
                .putString("group:" + to, TextUtils.join(",", apps)).apply();
    }

    /** The group an app is in, or null. */
    String groupOf(String pkg) {
        for (String g : groups()) if (groupApps(g).contains(pkg)) return g;
        return null;
    }

    // ---- Profiles ------------------------------------------------------------
    // Each profile has its own Top Shelf, hidden apps, Recent and background.
    // Profile 0 uses the original keys, so existing setups carry over as is.

    static final class Profile {
        final int id;
        final String name;

        Profile(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** A per-profile key: unchanged for profile 0. */
    private String pk(String base) {
        int id = currentProfile();
        return id == 0 ? base : base + "@" + id;
    }

    int currentProfile() {
        return sp.getInt("profile", 0);
    }

    void setCurrentProfile(int id) {
        sp.edit().putInt("profile", id).apply();
    }

    List<Profile> profiles() {
        List<Profile> out = new ArrayList<>();
        String raw = sp.getString("profiles", "0:Me");
        for (String line : raw.split("\n")) {
            int c = line.indexOf(':');
            if (c > 0) out.add(new Profile(Integer.parseInt(line.substring(0, c)), line.substring(c + 1)));
        }
        return out;
    }

    private void saveProfiles(List<Profile> list) {
        StringBuilder b = new StringBuilder();
        for (Profile p : list) b.append(b.length() == 0 ? "" : "\n").append(p.id).append(':').append(p.name);
        sp.edit().putString("profiles", b.toString()).apply();
    }

    String profileName() {
        for (Profile p : profiles()) if (p.id == currentProfile()) return p.name;
        return "Me";
    }

    int addProfile(String name) {
        List<Profile> list = profiles();
        int id = 0;
        for (Profile p : list) id = Math.max(id, p.id);
        list.add(new Profile(++id, name));
        saveProfiles(list);
        return id;
    }

    void renameProfile(int id, String name) {
        List<Profile> list = profiles();
        for (int i = 0; i < list.size(); i++) if (list.get(i).id == id) list.set(i, new Profile(id, name));
        saveProfiles(list);
    }

    void removeProfile(int id) {
        if (id == 0) return;
        List<Profile> list = profiles();
        list.removeIf(p -> p.id == id);
        saveProfiles(list);
        String s = "@" + id;
        SharedPreferences.Editor e = sp.edit();
        for (String k : new ArrayList<>(sp.getAll().keySet())) if (k.endsWith(s)) e.remove(k);
        if (currentProfile() == id) e.putInt("profile", 0);
        e.apply();
    }

    boolean askOnWake() {
        return sp.getBoolean("ask_profile_on_wake", true);
    }

    void setAskOnWake(boolean on) {
        sp.edit().putBoolean("ask_profile_on_wake", on).apply();
    }

    int ambientMinutes() {
        return sp.getInt("ambient_min", 3);
    }

    void setAmbientMinutes(int m) {
        sp.edit().putInt("ambient_min", m).apply();
    }

    String backgroundStyle() {
        return sp.getString(pk("bg_style"), ArtGen.NONE);
    }

    void setBackgroundStyle(String s) {
        sp.edit().putString(pk("bg_style"), s).apply();
    }

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
        String raw = sp.getString(pk(KEY_RECENTS), "");
        List<String> out = new ArrayList<>();
        if (!TextUtils.isEmpty(raw)) out.addAll(Arrays.asList(raw.split(",")));
        return out;
    }

    void noteLaunch(String pkg) {
        List<String> r = recents();
        r.remove(pkg);
        r.add(0, pkg);
        while (r.size() > MAX_RECENTS) r.remove(r.size() - 1);
        sp.edit().putString(pk(KEY_RECENTS), TextUtils.join(",", r))
                .putLong("launched:" + pkg, System.currentTimeMillis())
                .apply();
    }

    /** When Lumen last opened this app, or 0 if never. */
    long lastLaunch(String pkg) {
        return sp.getLong("launched:" + pkg, 0);
    }

    void forgetRecent(String pkg) {
        List<String> r = recents();
        if (r.remove(pkg)) sp.edit().putString(pk(KEY_RECENTS), TextUtils.join(",", r)).apply();
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
