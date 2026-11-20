package io.github.grimmmyy.lumen;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Opt-in weather for the info band and ambient mode, from Open-Meteo (no
 * account, no key). Off until a city is set; only the city name (to look up
 * its coordinates, once) and those coordinates are ever sent. Refreshed at
 * most every 30 minutes, and only while Lumen is on screen.
 */
final class Weather {
    interface Callback {
        void onWeather(String line); // main thread; null if unknown
    }

    static final class Place {
        final String name;
        final double lat, lon;

        Place(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static final String FILE = "weather";
    private static final long REFRESH_MS = 30 * 60_000;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());

    Weather(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    boolean enabled() {
        return sp.contains("lat");
    }

    String placeName() {
        return sp.getString("name", null);
    }

    boolean fahrenheit() {
        return sp.getBoolean("f", Locale.getDefault().getCountry().equals("US"));
    }

    void setFahrenheit(boolean f) {
        sp.edit().putBoolean("f", f).remove("at").apply();
    }

    void clear() {
        sp.edit().clear().apply();
    }

    /** The last known line, e.g. "12° Partly cloudy", or null. Never blocks. */
    String cached() {
        return enabled() ? sp.getString("line", null) : null;
    }

    /** Refreshes in the background if stale, then calls back with the line. */
    void refresh(Callback cb) {
        if (!enabled()) return;
        if (System.currentTimeMillis() - sp.getLong("at", 0) < REFRESH_MS) return;
        double lat = Double.longBitsToDouble(sp.getLong("lat", 0));
        double lon = Double.longBitsToDouble(sp.getLong("lon", 0));
        boolean f = fahrenheit();
        LumenApp.io().execute(() -> {
            String line = null;
            try {
                JSONObject cur = new JSONObject(get(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code&temperature_unit=%s",
                        lat, lon, f ? "fahrenheit" : "celsius"))).getJSONObject("current");
                line = Math.round(cur.getDouble("temperature_2m")) + "°  " + describe(cur.getInt("weather_code"));
                sp.edit().putString("line", line).putLong("at", System.currentTimeMillis()).apply();
            } catch (Exception ignored) {
                // offline: keep the last line
            }
            final String result = line;
            if (result != null) main.post(() -> cb.onWeather(result));
        });
    }

    /** Looks a city up (background thread) and saves it; calls back with the place or null. */
    void setCity(String query, java.util.function.Consumer<Place> cb) {
        LumenApp.io().execute(() -> {
            Place p = null;
            try {
                JSONArray r = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                        + URLEncoder.encode(query.trim(), "UTF-8"))).optJSONArray("results");
                if (r != null && r.length() > 0) {
                    JSONObject o = r.getJSONObject(0);
                    String name = o.getString("name") + (o.has("country_code") ? ", " + o.getString("country_code") : "");
                    p = new Place(name, o.getDouble("latitude"), o.getDouble("longitude"));
                    sp.edit().putString("name", name)
                            .putLong("lat", Double.doubleToLongBits(p.lat)).putLong("lon", Double.doubleToLongBits(p.lon))
                            .remove("at").remove("line").apply();
                }
            } catch (Exception ignored) {
                // offline or no match
            }
            final Place result = p;
            main.post(() -> cb.accept(result));
        });
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setRequestProperty("User-Agent", "Lumen-TV-launcher");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorms";
        return "";
    }
}
