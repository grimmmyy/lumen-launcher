package io.github.grimmmyy.lumen;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Settings backup and restore: Top Shelf, groups, profiles, hidden apps,
 * layout and options, weather place and update settings, as one JSON file.
 *
 * Back up writes Download/lumen-settings.json. Restore reads
 * /sdcard/Lumen/lumen-settings.json (or the Download copy). A fresh install
 * restores automatically when that file exists, so moving to another TV, or
 * reinstalling, keeps your layout.
 */
final class Backup {
    static final String NAME = "lumen-settings.json";
    private static final String[] FILES = {"lumen", "weather", "updates"};

    private Backup() {}

    static String export(Context ctx) throws Exception {
        JSONObject root = new JSONObject().put("version", 1).put("app", BuildConfigHelper.versionName(ctx));
        JSONObject files = new JSONObject();
        for (String name : FILES) {
            JSONObject f = new JSONObject();
            for (Map.Entry<String, ?> e : ctx.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                Object v = e.getValue();
                JSONObject entry = new JSONObject();
                if (v instanceof String) entry.put("t", "s").put("v", v);
                else if (v instanceof Boolean) entry.put("t", "b").put("v", v);
                else if (v instanceof Integer) entry.put("t", "i").put("v", v);
                else if (v instanceof Long) entry.put("t", "l").put("v", v);
                else if (v instanceof Float) entry.put("t", "f").put("v", (double) (Float) v);
                else if (v instanceof Set) entry.put("t", "set").put("v", new JSONArray((Set<?>) v));
                else continue;
                f.put(e.getKey(), entry);
            }
            files.put(name, f);
        }
        root.put("prefs", files);
        byte[] data = root.toString(1).getBytes(StandardCharsets.UTF_8);

        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, NAME);
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("can't write to Download");
            try (OutputStream o = ctx.getContentResolver().openOutputStream(uri)) {
                o.write(data);
            }
            return "Download/" + NAME;
        }
        File f = new File(ctx.getExternalFilesDir(null), NAME);
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(data);
        }
        return f.getAbsolutePath();
    }

    /** The backup file to restore from, or null. */
    static File find() {
        File a = new File(MainActivity.userDir(), NAME);
        if (a.canRead()) return a;
        File b = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), NAME);
        return b.canRead() ? b : null;
    }

    static void restore(Context ctx, File file) throws Exception {
        byte[] data;
        try (InputStream in = new FileInputStream(file)) {
            data = new byte[(int) file.length()];
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
        }
        JSONObject files = new JSONObject(new String(data, StandardCharsets.UTF_8)).getJSONObject("prefs");
        for (String name : FILES) {
            JSONObject f = files.optJSONObject(name);
            if (f == null) continue;
            SharedPreferences.Editor ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit();
            for (Iterator<String> it = f.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject e = f.getJSONObject(k);
                switch (e.getString("t")) {
                    case "s": ed.putString(k, e.getString("v")); break;
                    case "b": ed.putBoolean(k, e.getBoolean("v")); break;
                    case "i": ed.putInt(k, e.getInt("v")); break;
                    case "l": ed.putLong(k, e.getLong("v")); break;
                    case "f": ed.putFloat(k, (float) e.getDouble("v")); break;
                    case "set": {
                        Set<String> s = new HashSet<>();
                        JSONArray a = e.getJSONArray("v");
                        for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                        ed.putStringSet(k, s);
                        break;
                    }
                    default: break;
                }
            }
            ed.commit();
        }
    }

    /** On a fresh install, restores from the backup file if there is one. */
    static boolean restoreOnFirstRun(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("lumen", Context.MODE_PRIVATE);
        if (sp.contains("seeded") || sp.getBoolean("restore_checked", false)) return false;
        sp.edit().putBoolean("restore_checked", true).apply();
        File f = find();
        if (f == null) return false;
        try {
            restore(ctx, f);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
