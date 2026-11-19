package io.github.grimmmyy.lumen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executor;

/** Async poster loader with a small memory cache. Handles http(s), content and file sources. */
final class ImageLoader {
    interface Callback {
        void onLoaded(Bitmap bitmap); // main thread; null on failure
    }

    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private final Context ctx;
    private final Executor io;
    private final Handler main;
    private final LruCache<String, Bitmap> cache;

    ImageLoader(Context ctx, Executor io, Handler main) {
        this.ctx = ctx.getApplicationContext();
        this.io = io;
        this.main = main;
        // Small on purpose: this device has little RAM to spare.
        int size = (int) Math.min(Runtime.getRuntime().maxMemory() / 12, 12L * 1024 * 1024);
        cache = new LruCache<String, Bitmap>(size) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    void load(String source, int targetWidth, Callback cb) {
        String key = source + "@" + targetWidth;
        Bitmap hit = cache.get(key);
        if (hit != null) {
            cb.onLoaded(hit);
            return;
        }
        io.execute(() -> {
            Bitmap bmp = decodeSync(source, targetWidth);
            if (bmp != null) cache.put(key, bmp);
            main.post(() -> cb.onLoaded(bmp));
        });
    }

    void clear() {
        cache.evictAll();
    }

    /** Blocking decode, sampled down to about targetWidth. Background threads only. */
    Bitmap decodeSync(String source, int targetWidth) {
        byte[] data = read(source);
        if (data == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetWidth) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, d);
    }

    private byte[] read(String source) {
        HttpURLConnection conn = null;
        try {
            InputStream in;
            if (source.startsWith("http://") || source.startsWith("https://")) {
                conn = (HttpURLConnection) new URL(source).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(8000);
                in = conn.getInputStream();
            } else if (source.startsWith("/")) {
                in = new FileInputStream(source);
            } else {
                in = ctx.getContentResolver().openInputStream(Uri.parse(source));
            }
            if (in == null) return null;
            try (InputStream s = in; ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = s.read(chunk)) != -1) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > MAX_BYTES) return null;
                }
                return buf.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
