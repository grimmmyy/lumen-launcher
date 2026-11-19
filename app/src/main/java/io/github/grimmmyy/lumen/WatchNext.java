package io.github.grimmmyy.lumen;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the system "Watch Next" (Continue Watching) list that TV apps publish.
 * Needs READ_EPG_DATA to see other apps' rows. If the provider is missing
 * or access is denied, it returns an empty list and the row is hidden.
 */
final class WatchNext {
    static final class Item {
        final String title;
        final String subtitle;
        final String posterUri;
        final String intentUri;
        final String pkg;
        final float progress;

        Item(String title, String subtitle, String posterUri, String intentUri, String pkg, float progress) {
            this.title = title;
            this.subtitle = subtitle;
            this.posterUri = posterUri;
            this.intentUri = intentUri;
            this.pkg = pkg;
            this.progress = progress;
        }

        Intent toIntent() {
            if (intentUri == null) return null;
            try {
                return Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static final int LIMIT = 15;

    private WatchNext() {}

    static List<Item> load(Context ctx) {
        List<Item> out = new ArrayList<>();
        Uri uri = TvContract.WatchNextPrograms.CONTENT_URI;
        String[] proj = {
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
        };
        String sort = TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC";
        try (Cursor c = ctx.getContentResolver().query(uri, proj, null, null, sort)) {
            if (c == null) return out;
            while (c.moveToNext() && out.size() < LIMIT) {
                if (!c.isNull(8) && c.getInt(8) == 0) continue; // user removed it
                String title = c.getString(0);
                if (title == null) continue;
                String poster = c.getString(3) != null ? c.getString(3) : c.getString(2);
                long pos = c.isNull(6) ? 0 : c.getLong(6);
                long dur = c.isNull(7) ? 0 : c.getLong(7);
                float progress = dur > 0 ? Math.min(1f, (float) pos / dur) : 0f;
                out.add(new Item(title, c.getString(1), poster, c.getString(4), c.getString(5), progress));
            }
        } catch (Exception e) {
            // No TV provider on this device, or READ_EPG_DATA not granted.
        }
        return out;
    }
}
