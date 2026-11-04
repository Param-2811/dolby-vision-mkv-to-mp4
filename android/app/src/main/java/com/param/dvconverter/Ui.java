package com.param.dvconverter;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/** Material 3 components drawn on framework views, because the app ships without AndroidX. */
final class Ui {
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int color(Context c, int res) { return c.getColor(res); }

    /** Material type roles, in sp so the system font size applies. */
    static TextView text(Context c, CharSequence s, int colorRes, int sp, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(c.getColor(colorRes));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (medium) t.setTypeface(MEDIUM);
        return t;
    }

    static TextView oneLine(TextView t) {
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static GradientDrawable shape(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (stroke != 0) d.setStroke(dp(c, 1), stroke);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static Drawable ripple(Context c, Drawable content, Drawable mask) {
        int tint = c.getColor(R.color.on_surface) & 0x00FFFFFF | 0x1F000000;
        return new RippleDrawable(ColorStateList.valueOf(tint), content, mask);
    }

    static Drawable rippleRect(Context c) {
        return ripple(c, null, shape(c, Color.WHITE, 0, 0));
    }

    static Button button(Context c, String label, int fillRes, int textRes, int strokeRes, View.OnClickListener l) {
        Button b = new Button(c, null, 0, android.R.style.Widget_Material_Button_Borderless);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(MEDIUM);
        b.setTextColor(c.getColor(textRes));
        int fill = fillRes == 0 ? Color.TRANSPARENT : c.getColor(fillRes);
        Drawable face = shape(c, fill, strokeRes == 0 ? 0 : c.getColor(strokeRes), 24);
        b.setBackground(ripple(c, face, shape(c, Color.WHITE, 0, 24)));
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setMinWidth(dp(c, 48));
        b.setMinimumWidth(dp(c, 48));
        b.setPadding(dp(c, 24), 0, dp(c, 24), 0);
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    static Button filled(Context c, String label, View.OnClickListener l) {
        return button(c, label, R.color.primary, R.color.on_primary, 0, l);
    }

    static Button outlined(Context c, String label, View.OnClickListener l) {
        return button(c, label, 0, R.color.primary, R.color.outline, l);
    }

    static ImageButton icon(Context c, int res, int tintRes, String description, View.OnClickListener l) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(c.getColor(tintRes)));
        b.setContentDescription(description);
        b.setTooltipText(description);
        TypedValue v = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, v, true);
        b.setBackgroundResource(v.resourceId);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        b.setOnClickListener(l);
        return b;
    }

    /** Filter chip, 32dp tall inside the 48dp touch area its parent row gives it. */
    static TextView chip(Context c, String label, boolean selected, View.OnClickListener l) {
        TextView t = text(c, label, selected ? R.color.on_secondary_container : R.color.on_surface_variant, 14, true);
        Drawable face = shape(c, selected ? c.getColor(R.color.secondary_container) : Color.TRANSPARENT,
                selected ? 0 : c.getColor(R.color.outline), 8);
        t.setBackground(ripple(c, face, shape(c, Color.WHITE, 0, 8)));
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 32));
        t.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        if (selected) {
            Drawable mark = c.getDrawable(R.drawable.ic_check).mutate();
            mark.setTint(c.getColor(R.color.on_secondary_container));
            mark.setBounds(0, 0, dp(c, 18), dp(c, 18));
            t.setCompoundDrawablesRelative(mark, null, null, null);
            t.setCompoundDrawablePadding(dp(c, 8));
            t.setPaddingRelative(dp(c, 8), 0, dp(c, 16), 0);
        }
        t.setSelected(selected);
        t.setOnClickListener(l);
        return t;
    }

    static CheckBox checkbox(Context c, boolean on) {
        CheckBox b = new CheckBox(c);
        b.setChecked(on);
        b.setClickable(false);    // the whole row toggles it
        b.setFocusable(false);
        b.setButtonTintList(ColorStateList.valueOf(c.getColor(R.color.primary)));
        return b;
    }

    /** Material 3 switch shape on the framework Switch: a 52x32 pill with a 20dp thumb. */
    static Switch materialSwitch(Context c, boolean on) {
        Switch s = new Switch(c);
        GradientDrawable track = shape(c, Color.WHITE, 0, 16);
        track.setSize(dp(c, 52), dp(c, 32));
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.WHITE);
        thumb.setSize(dp(c, 20), dp(c, 20));
        s.setTrackDrawable(track);
        s.setThumbDrawable(new InsetDrawable(thumb, dp(c, 6)));   // Switch stretches the thumb to the track height
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setTrackTintList(new ColorStateList(states, new int[]{c.getColor(R.color.primary), c.getColor(R.color.surface_high)}));
        s.setThumbTintList(new ColorStateList(states, new int[]{c.getColor(R.color.on_primary), c.getColor(R.color.outline)}));
        s.setChecked(on);
        s.setClickable(false);    // the whole row toggles it
        s.setFocusable(false);
        return s;
    }

    /** One-or-two-line list row with a trailing control. The row owns the touch target. */
    static LinearLayout toggleRow(Context c, String title, String support, View control, boolean enabled, Runnable toggle) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 56));
        row.setPadding(dp(c, 24), dp(c, 8), dp(c, 16), dp(c, 8));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(c, title, enabled ? R.color.on_surface : R.color.on_surface_variant, 16, false));
        if (!support.isEmpty()) words.addView(text(c, support, R.color.on_surface_variant, 14, false));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (control != null) row.addView(control);
        if (enabled) {
            row.setBackground(rippleRect(c));
            row.setOnClickListener(v -> toggle.run());
        }
        return row;
    }

    static String language(String code) {
        if (code.equals("und") || code.isEmpty()) return "Unknown language";
        String name = new Locale(code).getDisplayLanguage();
        return name.isEmpty() ? code : name;
    }

    static String codec(String name) {
        return name.equals("subrip") ? "SRT" : name.equals("mov_text") ? "MP4 TEXT" : name.toUpperCase();
    }

    static String channels(int n) {
        return n == 1 ? "Mono" : n == 2 ? "Stereo" : n == 6 ? "5.1" : n == 8 ? "7.1" : n + " channels";
    }

    static String duration(double seconds) {
        if (seconds < 60) return Math.round(seconds) + " sec";
        long m = Math.round(seconds / 60);
        return m >= 60 ? m / 60 + " h " + m % 60 + " min" : m + " min";
    }

    /** Modal bottom sheet: drag handle, scrolling body, fixed footer so the action never scrolls away. */
    static Dialog sheet(Activity a, View body, View footer) {
        Dialog d = new Dialog(a, R.style.Sheet);
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        float r = dp(a, 28);
        bg.setColor(a.getColor(R.color.surface_container));
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bg);
        panel.setClipToOutline(true);

        LinearLayout handleArea = new LinearLayout(a);
        handleArea.setGravity(Gravity.CENTER);
        View handle = new View(a);
        handle.setBackground(shape(a, a.getColor(R.color.outline), 0, 2));
        handleArea.addView(handle, new LinearLayout.LayoutParams(dp(a, 32), dp(a, 4)));
        panel.addView(handleArea, new LinearLayout.LayoutParams(-1, dp(a, 36)));
        dragToDismiss(handleArea, panel, d);

        ScrollView scroll = new ScrollView(a);
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        if (footer != null) panel.addView(footer);

        panel.setOnApplyWindowInsetsListener((v, in) -> {
            Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(s.left, 0, s.right, s.bottom);
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            if (m.topMargin != s.top) { m.topMargin = s.top; v.setLayoutParams(m); }
            return in;
        });

        LinearLayout holder = new LinearLayout(a);
        holder.setGravity(Gravity.BOTTOM);
        holder.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        holder.setOnClickListener(v -> d.dismiss());   // tapping the scrim
        panel.setClickable(true);
        d.setContentView(holder);
        d.getWindow().setDecorFitsSystemWindows(false);
        d.getWindow().setLayout(-1, -1);
        d.getWindow().setGravity(Gravity.BOTTOM);
        return d;
    }

    private static void dragToDismiss(View grip, View panel, Dialog d) {
        float[] start = new float[1];
        grip.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    panel.setTranslationY(Math.max(0, e.getRawY() - start[0]));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (panel.getTranslationY() > dp(v.getContext(), 96)) {
                        d.dismiss();
                    } else {
                        ValueAnimator back = ValueAnimator.ofFloat(panel.getTranslationY(), 0);
                        back.setDuration(200);
                        back.addUpdateListener(x -> panel.setTranslationY((float) x.getAnimatedValue()));
                        back.start();
                    }
                    v.performClick();
                    return true;
                default:
                    return false;
            }
        });
    }
}
