package com.param.dvconverter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** JDK self-check of the mux decisions. Run with `android/check.sh`. */
public class PipelineCheck {
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static Pipeline.Info info(int profile, int compat) {
        Pipeline.Info p = new Pipeline.Info();
        p.dvProfile = profile; p.dvCompat = compat; p.duration = 60;
        p.tracks.add(new Pipeline.Track(1, "audio", "truehd", 8, "hin", "Atmos"));
        p.tracks.add(new Pipeline.Track(2, "audio", "eac3", 6, "eng", ""));
        p.tracks.add(new Pipeline.Track(3, "subtitle", "subrip", 0, "eng", ""));
        p.tracks.add(new Pipeline.Track(4, "subtitle", "hdmv_pgs_subtitle", 0, "hin", ""));
        return p;
    }

    static String mux(Pipeline.Info p, List<String> warn, Integer... skip) {
        return String.join(" ", Pipeline.buildMux(p, "in.mkv", "out.mp4",
                new HashSet<>(Arrays.asList(skip)), true, true, warn));
    }

    public static void main(String[] a) {
        check(Pipeline.refuse(info(5, 0)) == null, "P5 accepted");
        check(Pipeline.refuse(info(8, 1)) == null, "P8 accepted");
        check(Pipeline.refuse(info(7, 6)).contains("Windows"), "P7 refused");
        check(Pipeline.refuse(info(0, 0)).contains("No Dolby"), "non-DV refused");
        check(Pipeline.dvLabel(info(8, 1)).equals("8.1"), "P8 label");
        check(Pipeline.dvLabel(info(5, 0)).equals("5"), "P5 label");

        List<String> warn = new ArrayList<>();
        String m = mux(info(8, 1), warn);
        check(m.contains("-tag:v hvc1"), "P8 tag");
        check(m.contains("-c:a:0 eac3 -b:a:0 768k -ac:a:0 6"), "TrueHD 7.1 to EAC3 5.1");
        check(m.contains("-c:a:1 copy"), "EAC3 copied");
        check(m.contains("-map 0:3 -c:s:0 mov_text"), "text sub kept");
        check(!m.contains("0:4"), "bitmap sub dropped");
        check(warn.toString().contains("Bitmap"), "bitmap warned");
        check(m.endsWith("out.mp4"), "output last");

        warn.clear();
        m = mux(info(5, 0), warn, 1, 3);
        check(m.contains("-tag:v dvh1"), "P5 tag");
        check(warn.toString().contains("No HDR10 fallback"), "P5 warned");
        check(!m.contains("0:1 ") && !m.contains("0:3 "), "skipped tracks absent");
        check(m.contains("-map 0:2 -c:a:0 copy"), "stream specifier renumbers after a skip");

        List<String> noSubs = Pipeline.buildMux(info(8, 1), "i", "o", Collections.emptySet(), true, false, warn);
        check(!noSubs.contains("mov_text"), "subs off");
        check(info(8, 1).tracks.get(3).bitmap() && !info(8, 1).tracks.get(2).bitmap(), "bitmap subtitle detected");
        System.out.println("PipelineCheck OK");
    }
}
