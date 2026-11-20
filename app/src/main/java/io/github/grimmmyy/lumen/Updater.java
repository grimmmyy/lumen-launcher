package io.github.grimmmyy.lumen;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

/**
 * Updates from Lumen's GitHub releases, on the TV itself.
 *
 * A pushed version tag makes CI build, sign (with the one Lumen release key) and
 * publish a release. Lumen checks the latest release at most once a day while
 * it's on screen (Settings > Updates can turn that off or check now). Installing
 * downloads lumen.apk into Lumen's private cache, refuses anything that isn't
 * Lumen, isn't newer, or isn't signed with the same key as the installed copy,
 * then hands it to Android's installer, which asks for confirmation on screen.
 * Needs "install unknown apps" for Lumen (the setup scripts allow it).
 */
final class Updater {
    interface Progress {
        void onProgress(String message, boolean done); // main thread
    }

    static final String REPO = "grimmmyy/lumen-launcher";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long DAY = 24 * 60 * 60_000L;

    private final Context ctx;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());

    Updater(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        sp = this.ctx.getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    boolean autoCheck() {
        return sp.getBoolean("auto", true);
    }

    void setAutoCheck(boolean on) {
        sp.edit().putBoolean("auto", on).apply();
    }

    String current() {
        return BuildConfigHelper.versionName(ctx);
    }

    /** The newer release's version (e.g. "0.11.0"), or null if up to date or unknown. */
    String available() {
        String v = sp.getString("version", null);
        return v != null && newer(v, current()) && sp.getString("url", null) != null ? v : null;
    }

    String notes() {
        return sp.getString("notes", "");
    }

    long lastChecked() {
        return sp.getLong("checked", 0);
    }

    /** May Lumen hand an APK to the installer? ("Install unknown apps" for Lumen.) */
    boolean canInstall() {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    /** Checks GitHub in the background: always with {@code force}, else at most daily and only if automatic. */
    void check(boolean force, Runnable done) {
        if (!force && (!autoCheck() || System.currentTimeMillis() - lastChecked() < DAY)) return;
        LumenApp.io().execute(() -> {
            try {
                JSONObject rel = new JSONObject(get(API));
                String tag = rel.optString("tag_name", "");
                String url = null;
                long size = 0;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if ("lumen.apk".equals(a.optString("name"))) {
                            url = a.optString("browser_download_url");
                            size = a.optLong("size");
                        }
                    }
                }
                sp.edit().putString("version", tag.startsWith("v") ? tag.substring(1) : tag)
                        .putString("url", url).putLong("size", size)
                        .putString("notes", trimNotes(rel.optString("body", "")))
                        .putLong("checked", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                // offline or rate-limited: try again next time
                sp.edit().putLong("checked", System.currentTimeMillis() - DAY + 3 * 60 * 60_000L).apply();
            }
            if (done != null) main.post(done);
        });
    }

    /** Downloads, verifies and installs the available release. */
    void install(Progress p) {
        String url = sp.getString("url", null);
        String version = available();
        if (url == null || version == null) {
            p.onProgress("Lumen is up to date.", true);
            return;
        }
        if (!canInstall()) {
            p.onProgress("Lumen isn't allowed to install updates yet (see Setup).", true);
            return;
        }
        long size = sp.getLong("size", 0);
        LumenApp.io().execute(() -> {
            File apk = new File(ctx.getCacheDir(), "update.apk");
            try {
                download(url, apk, size, p);
                post(p, "Checking the download…", false);
                String problem = verify(apk);
                if (problem != null) throw new IOException(problem);
                post(p, "Installing… confirm on the TV.", false);
                installApk(apk);
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                post(p, "Update failed: " + e.getMessage(), true);
            }
        });
    }

    // ---- Steps ------------------------------------------------------------------

    private void download(String url, File out, long size, Progress p) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0, lastShown = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                total += n;
                if (size > 0 && total - lastShown > size / 20) {
                    lastShown = total;
                    post(p, "Downloading… " + (100 * total / size) + "%", false);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    /** Null if the APK is a newer Lumen signed with the installed copy's key, else why not. */
    private String verify(File apk) {
        PackageManager pm = ctx.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo got = pm.getPackageArchiveInfo(apk.getPath(), flags);
        if (got == null) return "the download isn't a valid app";
        if (!ctx.getPackageName().equals(got.packageName)) return "the download isn't Lumen";
        try {
            PackageInfo mine = pm.getPackageInfo(ctx.getPackageName(), flags);
            if (got.getLongVersionCode() <= mine.getLongVersionCode()) return "the download isn't newer";
            if (!Arrays.equals(signers(got), signers(mine))) return "the download is signed with a different key";
        } catch (PackageManager.NameNotFoundException e) {
            return "can't read the installed version";
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo p) {
        if (Build.VERSION.SDK_INT >= 28 && p.signingInfo != null) return p.signingInfo.getApkContentsSigners();
        return p.signatures;
    }

    private void installApk(File apk) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        params.setSize(apk.length());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("lumen.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent result = new Intent(ctx, InstallReceiver.class);
            int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(ctx, id, result, f).getIntentSender());
        }
    }

    // ---- Helpers ----------------------------------------------------------------

    private void post(Progress p, String msg, boolean done) {
        main.post(() -> p.onProgress(msg, done));
    }

    private static HttpURLConnection open(String url) throws IOException {
        // GitHub sends release downloads through redirects to its file storage.
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Lumen-TV-launcher");
            c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                url = c.getHeaderField("Location");
                c.disconnect();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("server said " + code);
            }
            return c;
        }
        throw new IOException("too many redirects");
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** First lines of the release notes, without Markdown clutter. */
    private static String trimNotes(String body) {
        StringBuilder b = new StringBuilder();
        for (String line : body.split("\\r?\\n")) {
            String l = line.replaceAll("^#+\\s*", "").replaceAll("^\\*\\s+", "• ").replaceAll("\\*\\*", "").trim();
            if (l.isEmpty() || l.startsWith("<!--")) continue;
            if (b.length() > 0) b.append('\n');
            b.append(l);
            if (b.length() > 500) break;
        }
        return b.toString();
    }

    /** True if version a is newer than b ("0.11.0" vs "0.10.0"). */
    static boolean newer(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
