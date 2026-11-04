package com.param.dvconverter;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Bottom sheet for one video (per-track choices) or several (per-language choices across them). */
final class ConvertSheet {
    private final MainActivity a;
    private final List<MainActivity.Item> items;
    private final LinearLayout body;
    private Dialog dialog;

    ConvertSheet(MainActivity a, List<MainActivity.Item> items) {
        this.a = a;
        this.items = items;
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
    }

    void show() {
        draw();
        LinearLayout foot = new LinearLayout(a);
        foot.setPadding(Ui.dp(a, 24), Ui.dp(a, 12), Ui.dp(a, 24), Ui.dp(a, 16));
        Button go = Ui.filled(a, items.size() == 1 ? "Convert" : "Convert " + items.size() + " videos", v -> {
            dialog.dismiss();
            a.convert(items);
        });
        foot.addView(go, new LinearLayout.LayoutParams(-1, -2));
        dialog = Ui.sheet(a, body, foot);
        dialog.show();
    }

    private void draw() {
        body.removeAllViews();
        header();
        Predicate<Pipeline.Track> hin = t -> t.isLang("hin", "hindi"), eng = t -> t.isLang("eng", "english");
        kind("audio", "Audio", new String[]{"All", "None", "Hindi", "English"},
                Arrays.asList(t -> true, t -> false, hin, eng));
        kind("subtitle", "Subtitles", new String[]{"All", "None", "Hindi + English", "Hindi", "English"},
                Arrays.asList(t -> true, t -> false, hin.or(eng), hin, eng));
    }

    private void header() {
        MainActivity.Item first = items.get(0);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 24), 0, Ui.dp(a, 24), Ui.dp(a, 4));
        boolean one = items.size() == 1;
        Bitmap thumb = first.thumb;
        if (one && thumb != null) {
            ImageView poster = new ImageView(a);
            poster.setImageBitmap(thumb);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setBackground(Ui.shape(a, a.getColor(R.color.surface_high), 0, 16));
            poster.setClipToOutline(true);
            poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 176));
            lp.bottomMargin = Ui.dp(a, 16);
            box.addView(poster, lp);
        }
        long bytes = 0;
        for (MainActivity.Item it : items) bytes += it.file.length();
        String size = Formatter.formatShortFileSize(a, bytes);
        box.addView(Ui.text(a, one ? first.file.getName() : items.size() + " videos", R.color.on_surface, 22, false));
        TextView meta = Ui.text(a, one
                ? Pipeline.sourceLabel(first.info) + "  ·  " + size + "  ·  " + Ui.duration(first.info.duration)
                : size + " in total", R.color.on_surface_variant, 14, false);
        meta.setPadding(0, Ui.dp(a, 4), 0, 0);
        box.addView(meta);
        body.addView(box);
    }

    private boolean matches(String kind, Predicate<Pipeline.Track> rule) {
        for (MainActivity.Item it : items)
            for (Pipeline.Track t : it.info.tracks)
                if (t.kind.equals(kind) && !t.bitmap() && rule.test(t) != it.keep.contains(t.index)) return false;
        return true;
    }

    private void apply(String kind, Predicate<Pipeline.Track> rule) {
        for (MainActivity.Item it : items)
            for (Pipeline.Track t : it.info.tracks) {
                if (!t.kind.equals(kind)) continue;
                if (rule.test(t)) it.keep.add(t.index); else it.keep.remove(t.index);
            }
        draw();
    }

    private void kind(String kind, String title, String[] names, List<Predicate<Pipeline.Track>> rules) {
        List<Pipeline.Track> tracks = new ArrayList<>();
        for (MainActivity.Item it : items)
            for (Pipeline.Track t : it.info.tracks) if (t.kind.equals(kind)) tracks.add(t);
        TextView head = Ui.text(a, title, R.color.on_surface, 16, true);
        head.setPadding(Ui.dp(a, 24), Ui.dp(a, 20), Ui.dp(a, 24), 0);
        body.addView(head);
        if (tracks.isEmpty()) {
            TextView none = Ui.text(a, "None in this video", R.color.on_surface_variant, 14, false);
            none.setPadding(Ui.dp(a, 24), Ui.dp(a, 4), Ui.dp(a, 24), 0);
            body.addView(none);
            return;
        }
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(a);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setPadding(Ui.dp(a, 24), 0, Ui.dp(a, 16), 0);
        boolean oneOn = false;
        for (int i = 0; i < names.length; i++) {
            Predicate<Pipeline.Track> rule = rules.get(i);
            boolean on = !oneOn && matches(kind, rule);    // only the first matching chip lights up
            oneOn |= on;
            TextView chip = Ui.chip(a, names[i], on, v -> apply(kind, rule));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(a, 32));
            lp.setMargins(0, Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8));
            chips.addView(chip, lp);
        }
        scroll.addView(chips);
        body.addView(scroll);
        if (items.size() == 1) trackRows(kind); else languageRows(kind);
    }

    private void trackRows(String kind) {
        MainActivity.Item it = items.get(0);
        for (Pipeline.Track t : it.info.tracks) {
            if (!t.kind.equals(kind)) continue;
            if (t.bitmap()) {
                body.addView(Ui.toggleRow(a, Ui.language(t.lang), "Image subtitles can't be saved in MP4", null, false, null));
                continue;
            }
            CheckBox box = Ui.checkbox(a, it.keep.contains(t.index));
            body.addView(Ui.toggleRow(a, Ui.language(t.lang), support(t), box, true, () -> {
                if (it.keep.contains(t.index)) it.keep.remove(t.index); else it.keep.add(t.index);
                draw();
            }));
        }
    }

    private String support(Pipeline.Track t) {
        StringBuilder s = new StringBuilder(Ui.codec(t.codec));
        if (t.kind.equals("audio")) {
            s.append("  ·  ").append(Ui.channels(t.channels));
            if (a.convertAudioOn() && !Pipeline.COPY_AUDIO.contains(t.codec)) s.append("  ·  converts to EAC3");
        }
        if (!t.title.isEmpty()) s.append("  ·  ").append(t.title);
        return s.toString();
    }

    private void languageRows(String kind) {
        Map<String, Integer> videos = new LinkedHashMap<>();
        for (MainActivity.Item it : items) {
            List<String> seen = new ArrayList<>();
            for (Pipeline.Track t : it.info.tracks) {
                if (!t.kind.equals(kind) || t.bitmap() || seen.contains(t.lang)) continue;
                seen.add(t.lang);
                videos.merge(t.lang, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : videos.entrySet()) {
            String lang = e.getKey();
            boolean kept = matchesKept(kind, lang);
            String support = "In " + e.getValue() + (e.getValue() == 1 ? " video" : " videos");
            body.addView(Ui.toggleRow(a, Ui.language(lang), support, Ui.checkbox(a, kept), true, () -> {
                for (MainActivity.Item it : items)
                    for (Pipeline.Track t : it.info.tracks) {
                        if (!t.kind.equals(kind) || !t.lang.equals(lang)) continue;
                        if (kept) it.keep.remove(t.index); else it.keep.add(t.index);
                    }
                draw();
            }));
        }
    }

    private boolean matchesKept(String kind, String lang) {
        for (MainActivity.Item it : items)
            for (Pipeline.Track t : it.info.tracks)
                if (t.kind.equals(kind) && t.lang.equals(lang) && !t.bitmap() && !it.keep.contains(t.index)) return false;
        return true;
    }
}
