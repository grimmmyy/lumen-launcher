package io.github.grimmmyy.lumen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * Ambient mode: after a few idle minutes on Home, the rows fade away and the
 * background drifts slowly behind a large clock, the date, the weather and
 * what's playing. The drift is stepped 10 times a second rather than every
 * frame: at this speed that is indistinguishable, and the TV renders a sixth
 * as many frames. The text block moves a little every minute against burn-in.
 */
final class AmbientView extends FrameLayout {
    private final ImageView scene;
    private final LinearLayout block;
    private final TextView weather;
    private final TextView playing;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private long startedAt;
    private boolean running;

    private final Runnable drift = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            double t = (SystemClock.uptimeMillis() - startedAt) / 1000.0;
            // Never below 1.12x: with up to 48 dp of drift, the scene always covers the
            // screen, so nothing has to be painted under it.
            float s = (float) (1.15 + 0.03 * Math.sin(t * 2 * Math.PI / 90));
            scene.setScaleX(s);
            scene.setScaleY(s);
            scene.setTranslationX((float) (48 * density * Math.sin(t * 2 * Math.PI / 130)));
            scene.setTranslationY((float) (24 * density * Math.cos(t * 2 * Math.PI / 110)));
            handler.postDelayed(this, 100);
        }
    };

    private final Runnable shift = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            block.animate().translationX((float) ((Math.random() - 0.5) * 60 * density))
                    .translationY((float) ((Math.random() - 0.5) * 40 * density)).setDuration(4000).start();
            handler.postDelayed(this, 60_000);
        }
    };

    AmbientView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        setAlpha(0f);
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        scene = new ImageView(ctx);
        scene.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(scene, new LayoutParams(-1, -1));

        View shade = new View(ctx);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BL_TR,
                new int[]{0xB3000000, 0x33000000, 0x00000000}));
        addView(shade, new LayoutParams(-1, -1));

        block = new LinearLayout(ctx);
        block.setOrientation(LinearLayout.VERTICAL);
        LayoutParams bl = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        bl.leftMargin = dp(80);
        bl.bottomMargin = dp(72);
        addView(block, bl);

        TextClock clock = new TextClock(ctx);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        style(clock, 120, 0xFFFFFFFF, Fonts.light(ctx));
        clock.setIncludeFontPadding(false);
        block.addView(clock);
        TextClock date = new TextClock(ctx);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        style(date, 28, 0xE6FFFFFF, Fonts.regular(ctx));
        block.addView(date);
        weather = new TextView(ctx);
        style(weather, 24, 0xCCFFFFFF, Fonts.regular(ctx));
        weather.setPadding(0, dp(6), 0, 0);
        block.addView(weather);
        playing = new TextView(ctx);
        style(playing, 22, 0xFF9CC2FF, Fonts.medium(ctx));
        playing.setPadding(0, dp(18), 0, 0);
        playing.setMaxWidth(dp(900));
        playing.setSingleLine(true);
        playing.setEllipsize(android.text.TextUtils.TruncateAt.END);
        block.addView(playing);
    }

    boolean isShowing() {
        return running;
    }

    void show(Bitmap image, String weatherLine, String nowPlaying) {
        scene.setImageBitmap(image);
        setWeather(weatherLine);
        setNowPlaying(nowPlaying);
        setVisibility(VISIBLE);
        animate().cancel();
        animate().alpha(1f).setDuration(900).withEndAction(null).start();
        running = true;
        startedAt = SystemClock.uptimeMillis();
        handler.removeCallbacksAndMessages(null);
        handler.post(drift);
        handler.postDelayed(shift, 60_000);
    }

    void hide(boolean quick) {
        running = false;
        handler.removeCallbacksAndMessages(null);
        animate().cancel();
        if (quick) {
            setAlpha(0f);
            setVisibility(GONE);
            scene.setImageDrawable(null);
            return;
        }
        animate().alpha(0f).setDuration(220).withEndAction(() -> {
            setVisibility(GONE);
            scene.setImageDrawable(null);
        }).start();
    }

    void setWeather(String line) {
        weather.setText(line);
        weather.setVisibility(line == null ? GONE : VISIBLE);
    }

    void setNowPlaying(String line) {
        playing.setText(line == null ? null : "♪  " + line);
        playing.setVisibility(line == null ? GONE : VISIBLE);
    }

    private void style(TextView t, float sp, int color, android.graphics.Typeface face) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        // No text shadow: the shade behind the text gives the contrast, and blurred
        // glyphs at 120sp are costly to draw.
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
