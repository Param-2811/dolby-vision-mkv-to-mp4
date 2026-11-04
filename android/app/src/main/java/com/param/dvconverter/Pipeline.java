package com.param.dvconverter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Port of Job.build_mux and the labels in dv_converter.py. No Android imports, so the JDK can test it. */
final class Pipeline {
    static final Set<String> COPY_AUDIO = new HashSet<>(Arrays.asList("eac3", "ac3", "aac"));
    static final Set<String> TEXT_SUBS = new HashSet<>(Arrays.asList(
            "subrip", "srt", "ass", "ssa", "text", "mov_text", "webvtt"));

    static final class Track {
        final int index, channels;
        final String kind, codec, lang, title;

        Track(int index, String kind, String codec, int channels, String lang, String title) {
            this.index = index; this.kind = kind; this.codec = codec;
            this.channels = channels; this.lang = lang; this.title = title;
        }

        String label() {
            String s = codec + "  " + lang;
            if (kind.equals("audio")) s += "  " + channels + "ch";
            return title.isEmpty() ? s : s + "  " + title;
        }

        boolean bitmap() { return kind.equals("subtitle") && !TEXT_SUBS.contains(codec); }

        /** Language tag, falling back to the title (some rips tag everything 'und'). */
        boolean isLang(String code, String word) {
            return lang.equals(code) || title.toLowerCase().contains(word);
        }
    }

    static final class Info {
        final List<Track> tracks = new ArrayList<>();
        double duration;
        boolean chapters, hdr10, hdr10plus;
        int dvProfile, dvCompat;    // 0 = no Dolby Vision
    }

    static String dvLabel(Info p) {
        if (p.dvProfile == 0) return "none";
        return p.dvProfile == 7 || p.dvProfile == 8 ? p.dvProfile + "." + p.dvCompat : String.valueOf(p.dvProfile);
    }

    /** Phone scope: remux of DV Profile 5 and 8 only. Null means the file can be converted. */
    static String refuse(Info p) {
        if (p.dvProfile == 5 || p.dvProfile == 8) return null;
        if (p.dvProfile == 7) return "Profile 7 needs the Windows app";
        if (p.dvProfile == 0) return "No Dolby Vision";
        return "Profile " + p.dvProfile + " is not supported";
    }

    static String sourceLabel(Info p) {
        if (p.dvProfile != 0) return "DV " + dvLabel(p) + (p.hdr10plus ? " + HDR10+" : "");
        return p.hdr10plus ? "HDR10+" : p.hdr10 ? "HDR10" : "SDR";
    }

    static List<String> buildMux(Info p, String src, String dst, Set<Integer> skip,
                                 boolean convertAudio, boolean subs, List<String> warn) {
        if (p.dvProfile == 5)
            warn.add("Profile 5 kept as-is. No HDR10 fallback: the player MUST support DV Profile 5 or colors will be wrong (green/purple).");
        if (p.hdr10plus)
            warn.add("Source has both HDR10+ and Dolby Vision. Both are kept, but MP4 players handle this inconsistently.");
        List<String> c = new ArrayList<>(Arrays.asList("-nostdin", "-y", "-i", src,
                "-map", "0:v:0", "-c:v", "copy", "-tag:v", p.dvProfile == 5 ? "dvh1" : "hvc1",
                "-strict", "unofficial", "-metadata:s:v:0", "handler_name=Video"));
        int ai = 0, si = 0;
        for (Track s : p.tracks) {
            if (!s.kind.equals("audio") || skip.contains(s.index)) continue;
            c.add("-map"); c.add("0:" + s.index);
            boolean mp4Audio = COPY_AUDIO.contains(s.codec);
            if (mp4Audio || !convertAudio) {
                c.add("-c:a:" + ai); c.add("copy");
                if (!mp4Audio) warn.add("Audio '" + s.codec + "' copied as-is; may not play in MP4.");
            } else {
                c.add("-c:a:" + ai); c.add("eac3");
                c.add("-b:a:" + ai); c.add(s.channels > 2 ? "768k" : "256k");
                if (s.channels > 6) { c.add("-ac:a:" + ai); c.add("6"); }  // ffmpeg's eac3 encoder caps at 5.1
            }
            c.add("-metadata:s:a:" + ai); c.add("language=" + s.lang);
            if (!s.title.isEmpty()) { c.add("-metadata:s:a:" + ai); c.add("title=" + s.title); }
            ai++;
        }
        for (Track s : p.tracks) {
            if (!subs || !s.kind.equals("subtitle") || skip.contains(s.index)) continue;
            if (!TEXT_SUBS.contains(s.codec)) {
                warn.add("Bitmap subtitle (" + s.codec + ") skipped; MP4 only supports text subs.");
                continue;
            }
            c.add("-map"); c.add("0:" + s.index);
            c.add("-c:s:" + si); c.add("mov_text");
            c.add("-metadata:s:s:" + si); c.add("language=" + s.lang);
            if (!s.title.isEmpty()) { c.add("-metadata:s:s:" + si); c.add("title=" + s.title); }
            si++;
        }
        if (p.chapters) { c.add("-map_chapters"); c.add("0"); }
        c.addAll(Arrays.asList("-dn", "-movflags", "+faststart", dst));
        return c;
    }
}
