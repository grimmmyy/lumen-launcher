package io.github.grimmmyy.lumen;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Setup and health: everything Lumen needs, its live status, and how to fix
 * each. Some settings can only be changed with ADB from a computer: Fire OS
 * hides them, and Fire OS 8 refuses ADB connections made from the TV itself
 * (tested: the TV's adbd hangs up on a local connection within a millisecond,
 * while the same client works from a PC). For those, Setup shows the exact
 * command, and tools/setup.ps1 / tools/setup.sh run them all in one go.
 */
public class SetupActivity extends Activity {
    static final int OK = 0, ACTION = 1, INFO = 2;
    private static final int GREEN = 0xFF5BD68A, AMBER = 0xFFFFB84D, GREY = 0xFF8A93A6;
    private static final String HOME_SERVICE = "io.github.grimmmyy.lumen/io.github.grimmmyy.lumen.HomeWatcherService";
    private static final int REQ_STORAGE = 9;

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView summary;
    private TextView status;

    /** Set by a background check: null = not known yet. */
    private volatile Boolean compiled;
    private volatile boolean compileChecked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        checkCompiled();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- Checks -------------------------------------------------------------

    private static final class Check {
        final String title;
        final String detail;
        final int state;
        final String action;
        final Runnable run;

        Check(String title, String detail, int state, String action, Runnable run) {
            this.title = title;
            this.detail = detail;
            this.state = state;
            this.action = action;
            this.run = run;
        }
    }

    private List<Check> checks() {
        List<Check> c = new ArrayList<>();
        String pkg = getPackageName();

        // Home button
        boolean home = isHomeButtonOn(this);
        c.add(new Check("Home button opens Lumen",
                home ? "On. Lumen opens whenever the Fire TV home screen appears."
                        : "Off. The Home button goes to the Amazon home screen.",
                home ? OK : ACTION, home ? null : "How",
                home ? null : () -> showCommands("Turn on the Home button",
                        "Fire TV hides this setting, so turn it on from a computer with ADB:",
                        "adb shell \"" + homeButtonCommand(this).replace("'", "") + "\"")));

        // Now Playing
        boolean np = NowPlaying.available(this);
        c.add(new Check("Now Playing", np
                ? "On. What's playing in any app shows at the top of Home, with its artwork."
                : "Off. Shows what's playing (music, shows) at the top of Home. Lumen reads only the "
                + "player, never your notifications.", np ? OK : ACTION, np ? null : "How",
                np ? null : () -> showCommands("Turn on Now Playing",
                        "Android asks for notification access to read media players; Fire TV hides that "
                        + "setting, so turn it on from a computer with ADB:",
                        "adb shell cmd notification allow_listener " + pkg + "/" + pkg + ".NowPlayingService")));

        // Ahead-of-time compile
        if (compiled == null) {
            c.add(new Check("Fast scrolling code", !compileChecked ? "Checking…"
                    : "Android compiles Lumen ahead of time during idle maintenance, often overnight. "
                    + "This Android version doesn't let Lumen check whether it has.", INFO, compileChecked ? "Do it now" : null,
                    !compileChecked ? null : () -> showCommands("Compile Lumen now",
                            "From a computer with ADB (takes about 10 seconds):",
                            "adb shell cmd package compile -m speed-profile -f " + pkg)));
        } else if (compiled) {
            c.add(new Check("Fast scrolling code", "Compiled ahead of time.", OK, null, null));
        } else {
            c.add(new Check("Fast scrolling code", "Not compiled yet: Android does it during idle maintenance, "
                    + "often overnight. Until then the first scrolls can stutter.", ACTION, "Do it now",
                    () -> showCommands("Compile Lumen now", "From a computer with ADB (takes about 10 seconds):",
                            "adb shell cmd package compile -m speed-profile -f " + pkg)));
        }

        // Wallpapers
        String perm = Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_IMAGES"
                : "android.permission.READ_EXTERNAL_STORAGE";
        boolean photos = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
        c.add(new Check("Your wallpapers", photos ? "Allowed. Images in /sdcard/Lumen/wallpapers are shown."
                : "Optional: lets Lumen show your own images from /sdcard/Lumen/wallpapers.",
                photos ? OK : INFO, photos ? null : "Allow",
                photos ? null : () -> requestPermissions(new String[]{perm}, REQ_STORAGE)));

        // Don't keep activities (developer option)
        boolean dka = Settings.Global.getInt(getContentResolver(), Settings.Global.ALWAYS_FINISH_ACTIVITIES, 0) != 0;
        c.add(new Check("\"Don't keep activities\"", dka
                ? "On. Every app, Lumen included, rebuilds its screen when you return to it (about 0.1 s for Lumen)."
                : "Off. Apps resume where you left them.", INFO, null, null));

        // Memory and storage
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long freeStorage = fs.getAvailableBytes();
        boolean lowStorage = freeStorage < 500L * 1024 * 1024;
        c.add(new Check("Memory and storage", String.format(java.util.Locale.US,
                "%s free of %s memory%s  ·  %s storage free", gb(mi.availMem), gb(mi.totalMem),
                mi.lowMemory ? " (low)" : "", gb(freeStorage)),
                lowStorage || mi.lowMemory ? ACTION : INFO,
                lowStorage ? "Manage apps" : null,
                lowStorage ? () -> openSetting(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS) : null));
        return c;
    }

    static boolean isHomeButtonOn(Context ctx) {
        String enabled = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(ctx, HomeWatcherService.class);
        for (String s : enabled.split(":")) if (me.equals(ComponentName.unflattenFromString(s))) return true;
        return false;
    }

    /** Adds Lumen's service to the enabled list, keeping any others the user has. */
    static String homeButtonCommand(Context ctx) {
        String enabled = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String list = TextUtils.isEmpty(enabled) || "null".equals(enabled) ? HOME_SERVICE : enabled + ":" + HOME_SERVICE;
        return "settings put secure enabled_accessibility_services '" + list + "' && settings put secure accessibility_enabled 1";
    }

    // ---- Background work ----------------------------------------------------

    /** Asks AndroidX whether the baseline profile has been compiled (reads Lumen's own profile files). */
    private void checkCompiled() {
        com.google.common.util.concurrent.ListenableFuture<androidx.profileinstaller.ProfileVerifier.CompilationStatus> f =
                androidx.profileinstaller.ProfileVerifier.getCompilationStatusAsync();
        f.addListener(() -> {
            try {
                androidx.profileinstaller.ProfileVerifier.CompilationStatus st = f.get();
                // Android 11 (Fire OS 8) keeps the profile where apps can't read it:
                // the status can't be known there.
                compiled = st.getProfileInstallResultCode()
                        == androidx.profileinstaller.ProfileVerifier.CompilationStatus.RESULT_CODE_ERROR_UNSUPPORTED_API_VERSION
                        ? null : st.isCompiledWithProfile();
                compileChecked = true;
            } catch (Exception e) {
                compiled = null;
            }
            main.post(this::render);
        }, LumenApp.io());
    }

    /** A panel with the exact computer commands for a fix, in large monospace type. */
    private void showCommands(String title, String intro, String commands) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(28), dp(12), dp(28), dp(8));
        TextView introView = text(intro, 17, 0xCCFFFFFF, false);
        box.addView(introView);
        TextView cmd = text(commands, 17, Color.WHITE, false);
        cmd.setTypeface(Typeface.MONOSPACE);
        cmd.setTextIsSelectable(false);
        cmd.setPadding(dp(18), dp(14), dp(18), dp(14));
        cmd.setBackground(rounded(0x33000000, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        box.addView(cmd, lp);
        TextView more = text("Or run tools/setup.ps1 (Windows) or tools/setup.sh from Lumen's GitHub page: "
                + "it does every step on this page at once.", 15, 0x99FFFFFF, false);
        more.setPadding(0, dp(14), 0, 0);
        box.addView(more);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setView(box).setPositiveButton("OK", null).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        render();
    }

    private void openSetting(String... actions) {
        for (String a : actions) {
            try {
                startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
                // try the next one
            }
        }
    }

    // ---- Layout -------------------------------------------------------------

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B0E15, 0xFF121826, 0xFF1A1426});
        root.setBackground(bg);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(dp(72), dp(56), dp(56), dp(40));
        root.addView(columns, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        columns.addView(left, new LinearLayout.LayoutParams(0, -1, 0.36f));
        left.addView(text("Lumen", 18, 0x99FFFFFF, false));
        TextView title = text("Setup", 52, Color.WHITE, false);
        title.setTypeface(Fonts.light(this));
        left.addView(title);
        summary = text("", 20, 0xCCFFFFFF, false);
        summary.setPadding(0, dp(10), dp(40), 0);
        left.addView(summary);
        status = text("Changes here take effect right away.", 17, 0x80FFFFFF, false);
        status.setPadding(0, dp(24), dp(40), 0);
        left.addView(status);
        View spacer = new View(this);
        left.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        left.addView(text("Lumen " + BuildConfigHelper.versionName(this) + "  ·  Back to return", 15, 0x80FFFFFF, false));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        columns.addView(scroll, new LinearLayout.LayoutParams(0, -1, 0.64f));
        return root;
    }

    private void render() {
        if (list == null) return;
        View focused = getCurrentFocus();
        int focusIndex = focused != null ? list.indexOfChild(focused) : 0;
        list.removeAllViews();
        List<Check> checks = checks();
        int ready = 0, total = 0;
        for (Check c : checks) {
            if (c.state != INFO) total++;
            if (c.state == OK) ready++;
            list.addView(row(c));
        }
        summary.setText(ready == total ? "Everything is set up." : ready + " of " + total + " ready.");
        View target = list.getChildAt(Math.max(0, Math.min(focusIndex, list.getChildCount() - 1)));
        if (target != null) target.requestFocus();
    }

    private View row(Check c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), dp(18), dp(24), dp(18));
        row.setFocusable(true);
        row.setClickable(true);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, rounded(0x26FFFFFF, 0x66FFFFFF));
        sl.addState(new int[]{}, rounded(0x00000000, 0x00000000));
        row.setBackground(sl);
        row.setOnFocusChangeListener((v, has) ->
                v.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f).setDuration(140).start());
        if (c.run != null) row.setOnClickListener(v -> c.run.run());

        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(c.state == OK ? GREEN : c.state == ACTION ? AMBER : GREY);
        dot.setBackground(d);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(12), dp(12));
        dl.rightMargin = dp(20);
        row.addView(dot, dl);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(c.title, 21, Color.WHITE, true));
        TextView detail = text(c.detail, 16, 0xB3FFFFFF, false);
        detail.setPadding(0, dp(4), 0, 0);
        texts.addView(detail);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        if (c.action != null) {
            TextView act = text(c.action, 16, Color.WHITE, true);
            act.setPadding(dp(18), dp(8), dp(18), dp(8));
            act.setBackground(rounded(0x33FFFFFF, 0));
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
            al.leftMargin = dp(20);
            row.addView(act, al);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.bottomMargin = dp(8);
        row.setLayoutParams(rl);
        return row;
    }

    private GradientDrawable rounded(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(2), stroke);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(medium ? Fonts.semibold(this) : Fonts.regular(this));
        t.setLayoutParams(new ViewGroup.LayoutParams(-2, -2));
        return t;
    }

    private static String gb(long bytes) {
        return String.format(java.util.Locale.US, bytes >= 1L << 30 ? "%.1f GB" : "%.0f MB",
                bytes >= 1L << 30 ? bytes / (double) (1L << 30) : bytes / (double) (1L << 20));
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
