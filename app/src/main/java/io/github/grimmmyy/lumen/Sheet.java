package io.github.grimmmyy.lumen;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Lumen's own menus: a panel that slides in from the right over a dimmed
 * screen, in Lumen's typeface and focus style, instead of the system dialogs.
 * Rows can be actions, choices (with a check), switches (stay open), text
 * fields with buttons, or plain text. Back closes it; while it's open the
 * screen behind it can't take focus, and focus returns where it was after.
 *
 * Built in the activity's own view tree (no extra window), so it opens in a
 * frame or two. Usage: {@code Sheet.with(activity, "Title").item(...).show();}
 */
final class Sheet {
    private static final int ACCENT = 0xFF9CC2FF;

    private final Activity activity;
    private final String title;
    private CharSequence subtitle;
    private String caption = "Lumen";
    private final List<Row> rows = new ArrayList<>();
    private Runnable onClose;

    private FrameLayout root;
    private TextView subtitleView;
    private LinearLayout panel;
    private View previousFocus;
    private final List<View> blocked = new ArrayList<>();
    private boolean closing;

    private static final class Row {
        int kind; // 0 action, 1 choice, 2 toggle, 3 input, 4 text, 5 button
        CharSequence label;
        String value;
        boolean on;
        Runnable action;
        java.util.function.Consumer<Boolean> toggle;
        EditText input;
        boolean keepOpen;
    }

    private Sheet(Activity activity, String title) {
        this.activity = activity;
        this.title = title;
    }

    static Sheet with(Activity activity, String title) {
        return new Sheet(activity, title);
    }

    Sheet caption(String c) {
        caption = c;
        return this;
    }

    Sheet subtitle(CharSequence s) {
        subtitle = s;
        return this;
    }

    /** A row that runs its action and closes the panel. */
    Sheet item(CharSequence label, Runnable action) {
        return item(label, null, action);
    }

    /** An action row with a value on the right, e.g. "Background  ·  Aurora". */
    Sheet item(CharSequence label, String value, Runnable action) {
        Row r = new Row();
        r.label = label;
        r.value = value;
        r.action = action;
        rows.add(r);
        return this;
    }

    /** One of several options; the current one has a check. */
    Sheet choice(CharSequence label, boolean selected, Runnable action) {
        Row r = new Row();
        r.kind = 1;
        r.label = label;
        r.on = selected;
        r.action = action;
        rows.add(r);
        return this;
    }

    /** An on/off switch; the panel stays open. */
    Sheet toggle(CharSequence label, boolean on, java.util.function.Consumer<Boolean> change) {
        Row r = new Row();
        r.kind = 2;
        r.label = label;
        r.on = on;
        r.toggle = change;
        rows.add(r);
        return this;
    }

    /** A text field; read it from a button's action. */
    EditText input(String hint, String initial) {
        Row r = new Row();
        r.kind = 3;
        EditText e = new EditText(activity);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(initial);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        r.input = e;
        rows.add(r);
        return e;
    }

    /** A button row (for forms); closes the panel unless {@code keepOpen}. */
    Sheet button(CharSequence label, boolean keepOpen, Runnable action) {
        Row r = new Row();
        r.kind = 5;
        r.label = label;
        r.action = action;
        r.keepOpen = keepOpen;
        rows.add(r);
        return this;
    }

    /** A command or code, in monospace on a dark box. */
    Sheet code(CharSequence t) {
        Row r = new Row();
        r.kind = 6;
        r.label = t;
        rows.add(r);
        return this;
    }

    /** Plain text, not focusable (explanations, commands). */
    Sheet text(CharSequence t) {
        Row r = new Row();
        r.kind = 4;
        r.label = t;
        rows.add(r);
        return this;
    }

    /** Changes the subtitle of an open panel (e.g. when sizes have been measured). */
    void updateSubtitle(CharSequence s) {
        subtitle = s;
        if (subtitleView != null) subtitleView.setText(s);
    }

    Sheet onClose(Runnable r) {
        onClose = r;
        return this;
    }

    // ---- Showing --------------------------------------------------------------

    void show() {
        ViewGroup content = activity.findViewById(android.R.id.content);
        Sheet open = current(activity);
        if (open != null) open.dismiss(false);
        previousFocus = activity.getCurrentFocus();
        // Nothing behind the panel may take focus while it's open.
        for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            if (v instanceof ViewGroup && ((ViewGroup) v).getDescendantFocusability() != ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
                ((ViewGroup) v).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                blocked.add(v);
            }
        }
        root = build();
        root.setTag(R.id.section_tag, this);
        content.addView(root, new ViewGroup.LayoutParams(-1, -1));
        panel.setTranslationX(dp(80));
        panel.setAlpha(0f);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(160).start();
        panel.animate().translationX(0f).alpha(1f).setDuration(220).setInterpolator(new DecelerateInterpolator(2f)).start();
        focusFirst();
    }

    void dismiss() {
        dismiss(true);
    }

    private void dismiss(boolean animate) {
        if (closing || root == null) return;
        closing = true;
        final FrameLayout r = root;
        Runnable remove = () -> {
            ViewGroup parent = (ViewGroup) r.getParent();
            if (parent != null) parent.removeView(r);
        };
        for (View v : blocked) ((ViewGroup) v).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        blocked.clear();
        if (previousFocus != null && previousFocus.isAttachedToWindow()) previousFocus.requestFocus();
        if (animate) {
            panel.animate().translationX(dp(60)).alpha(0f).setDuration(160).start();
            r.animate().alpha(0f).setDuration(170).withEndAction(remove).start();
        } else {
            remove.run();
        }
        if (onClose != null) onClose.run();
    }

    /** Routes Back to the open panel. Call from the activity's dispatchKeyEvent. */
    static boolean handleKey(Activity a, KeyEvent e) {
        Sheet s = current(a);
        if (s == null) return false;
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) s.dismiss();
            return true;
        }
        // Menu while a panel is open does nothing (no stacked menus).
        return e.getKeyCode() == KeyEvent.KEYCODE_MENU;
    }

    static boolean isOpen(Activity a) {
        return current(a) != null;
    }

    private static Sheet current(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) return null;
        for (int i = content.getChildCount() - 1; i >= 0; i--) {
            Object t = content.getChildAt(i).getTag(R.id.section_tag);
            if (t instanceof Sheet && !((Sheet) t).closing) return (Sheet) t;
        }
        return null;
    }

    // ---- Layout ---------------------------------------------------------------

    private FrameLayout build() {
        FrameLayout r = new FrameLayout(activity);
        r.setTranslationZ(8f);
        r.setBackgroundColor(0x99000000);
        r.setClickable(true);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(44), dp(52), dp(36), dp(36));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF7151A26, 0xF70C0F17});
        panel.setBackground(bg);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(600), -1, Gravity.END);
        r.addView(panel, pl);

        TextView cap = text(caption, 15, 0x99FFFFFF, Fonts.medium(activity));
        cap.setLetterSpacing(0.08f);
        cap.setText(caption.toUpperCase(java.util.Locale.getDefault()));
        panel.addView(cap);
        TextView t = text(title, 32, Color.WHITE, Fonts.semibold(activity));
        t.setPadding(0, dp(6), 0, 0);
        panel.addView(t);
        if (subtitle != null) {
            TextView st = text(subtitle, 17, 0xB3FFFFFF, Fonts.regular(activity));
            st.setPadding(0, dp(10), 0, 0);
            st.setLineSpacing(0, 1.15f);
            panel.addView(st);
            subtitleView = st;
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(22), 0, dp(10));
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        for (Row row : rows) list.addView(rowView(row));
        return r;
    }

    private View rowView(Row row) {
        if (row.kind == 4) {
            TextView tv = text(row.label, 16, 0xCCFFFFFF, Fonts.regular(activity));
            tv.setPadding(dp(16), dp(6), dp(16), dp(10));
            tv.setLineSpacing(0, 1.15f);
            return tv;
        }
        if (row.kind == 6) {
            TextView tv = text(row.label, 16, Color.WHITE, Typeface.MONOSPACE);
            tv.setPadding(dp(16), dp(12), dp(16), dp(12));
            GradientDrawable box = new GradientDrawable();
            box.setCornerRadius(dp(10));
            box.setColor(0x4D000000);
            tv.setBackground(box);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(12);
            tv.setLayoutParams(lp);
            return tv;
        }
        if (row.kind == 3) {
            EditText e = row.input;
            e.setTextColor(Color.WHITE);
            e.setHintTextColor(0x66FFFFFF);
            e.setTypeface(Fonts.regular(activity));
            e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            e.setPadding(dp(18), dp(14), dp(18), dp(14));
            e.setBackground(states(0x1FFFFFFF, 0x33FFFFFF, true));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(12);
            e.setLayoutParams(lp);
            return e;
        }
        LinearLayout v = new LinearLayout(activity);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(18), dp(15), dp(18), dp(15));
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(row.kind == 5 ? states(0x1AFFFFFF, 0xFFFFFFFF, false) : states(0x00000000, 0x26FFFFFF, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(4);
        v.setLayoutParams(lp);

        TextView label = text(row.label, 20, Color.WHITE, row.kind == 5 ? Fonts.semibold(activity) : Fonts.medium(activity));
        if (row.kind == 5) label.setGravity(Gravity.CENTER);
        v.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView end = text("", 18, 0xB3FFFFFF, Fonts.regular(activity));
        if (row.kind == 0 && row.value != null) end.setText(row.value);
        if (row.kind == 1 && row.on) {
            end.setText("✓");
            end.setTextColor(ACCENT);
            end.setTypeface(Fonts.semibold(activity));
        }
        if (row.kind == 2) setSwitch(end, row.on);
        if (end.length() > 0) {
            LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-2, -2);
            el.leftMargin = dp(16);
            v.addView(end, el);
        }
        if (row.kind == 5) {
            // A filled button: dark text when focused (white fill).
            v.setOnFocusChangeListener((x, has) -> label.setTextColor(has ? 0xFF10131A : Color.WHITE));
        }
        v.setOnClickListener(x -> {
            if (row.kind == 2) {
                row.on = !row.on;
                setSwitch(end, row.on);
                if (row.toggle != null) row.toggle.accept(row.on);
                return;
            }
            if (!row.keepOpen) dismiss();
            if (row.action != null) row.action.run();
        });
        return v;
    }

    private void setSwitch(TextView end, boolean on) {
        end.setText(on ? "On" : "Off");
        end.setTextColor(on ? ACCENT : 0x80FFFFFF);
        end.setTypeface(Fonts.semibold(activity));
    }

    private void focusFirst() {
        LinearLayout list = (LinearLayout) ((ScrollView) panel.getChildAt(panel.getChildCount() - 1)).getChildAt(0);
        // Prefer the checked choice, then the first focusable row.
        View first = null;
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (!c.isFocusable()) continue;
            if (first == null) first = c;
            Row row = rows.get(i);
            if (row.kind == 1 && row.on) {
                first = c;
                break;
            }
        }
        if (first != null) first.requestFocus();
    }

    private StateListDrawable states(int normal, int focused, boolean stroke) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable f = new GradientDrawable();
        f.setCornerRadius(dp(12));
        f.setColor(focused);
        if (stroke) f.setStroke(dp(2), 0x99FFFFFF);
        GradientDrawable n = new GradientDrawable();
        n.setCornerRadius(dp(12));
        n.setColor(normal);
        s.addState(new int[]{android.R.attr.state_focused}, f);
        s.addState(new int[]{}, n);
        return s;
    }

    private TextView text(CharSequence s, float sp, int color, Typeface face) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
