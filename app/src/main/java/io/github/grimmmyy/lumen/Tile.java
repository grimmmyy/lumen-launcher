package io.github.grimmmyy.lumen;

import android.graphics.drawable.Drawable;

/** What a single tile shows and does. Rows are lists of these. */
final class Tile {
    String key;          // unique per screen; used to restore focus after a refresh
    int row;             // 0 = Top Shelf
    String title;
    String subtitle;
    Drawable banner;     // full-bleed TV banner
    Drawable icon;       // centered icon when there is no banner
    String imageUri;     // async poster (Continue Watching)
    int color = 0xFF1B2333;
    float progress;      // 0..1, shows a bar when > 0
    String backdropPkg;  // whose wallpaper to show when focused
    Runnable onClick;
    Runnable onLongClick;
}
