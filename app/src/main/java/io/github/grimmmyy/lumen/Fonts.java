package io.github.grimmmyy.lumen;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Inter, bundled as four static weights subset to Latin (about 94 KB each).
 * Loaded once per process, on first use. License: licenses/Inter-OFL.txt.
 */
final class Fonts {
    private static Typeface light, regular, medium, semibold;

    private Fonts() {}

    private static void load(Context c) {
        if (regular != null) return;
        android.content.res.Resources r = c.getApplicationContext().getResources();
        light = r.getFont(R.font.inter_light);
        regular = r.getFont(R.font.inter_regular);
        medium = r.getFont(R.font.inter_medium);
        semibold = r.getFont(R.font.inter_semibold);
    }

    static Typeface light(Context c) {
        load(c);
        return light;
    }

    static Typeface regular(Context c) {
        load(c);
        return regular;
    }

    static Typeface medium(Context c) {
        load(c);
        return medium;
    }

    static Typeface semibold(Context c) {
        load(c);
        return semibold;
    }
}
