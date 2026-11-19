package io.github.grimmmyy.lumen;

/** What a single tile shows and does. Rows are lists of these. */
final class Tile {
    String key;          // unique per screen; used to restore focus after a refresh
    String title;
    String subtitle;     // shown in the header when focused
    String caption;      // small second line drawn on text-only tiles
    AppEntry app;        // app art comes from ArtCache when set
    String imageUri;     // poster art (Continue Watching)
    int color = 0xFF1B2333;
    float progress;      // 0..1, draws a progress bar when > 0
    String backdropPkg;  // whose wallpaper to show when focused
    boolean resetsScroll; // focusing this tile scrolls the page back to the top
    Runnable onClick;
    Runnable onLongClick;
}
