package com.param.dvconverter;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Runs the bundled binaries. They ship as lib*.so because only nativeLibraryDir is executable. */
final class Tools {
    interface Progress { void percent(double pct); }

    static String bin(Context c, String name) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "lib" + name + ".so").getPath();
    }

    private static List<String> cmd(String binary, String... args) {
        List<String> c = new ArrayList<>();
        c.add(binary);
        c.addAll(Arrays.asList(args));
        return c;
    }

    /** Drains stderr on its own thread so a chatty tool cannot block on a full pipe. */
    private static StringBuilder drain(InputStream in) {
        StringBuilder sb = new StringBuilder();
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                for (String l; (l = r.readLine()) != null; ) {
                    synchronized (sb) {
                        sb.append(l).append('\n');
                        if (sb.length() > 4000) sb.delete(0, sb.length() - 2000);
                    }
                }
            } catch (IOException ignored) {   // the process was killed
            }
        });
        t.setDaemon(true);
        t.start();
        return sb;
    }

    private static String tail(StringBuilder sb) {
        synchronized (sb) { return sb.toString().trim(); }
    }

    private static String capture(List<String> c) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(c).start();
        StringBuilder err = drain(p.getErrorStream());
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
            for (String l; (l = r.readLine()) != null; ) out.append(l).append('\n');
        }
        if (p.waitFor() != 0) throw new IOException("ffprobe failed: " + tail(err));
        return out.toString();
    }

    /** Port of probe() in dv_converter.py. */
    static Pipeline.Info probe(Context ctx, String path) throws IOException, InterruptedException {
        String probe = bin(ctx, "ffprobe");
        String json = capture(cmd(probe, "-v", "error", "-print_format", "json",
                "-show_streams", "-show_format", "-show_chapters", path));
        // HDR10+ lives in per-frame SEI; peek at the first frames only
        String frames = capture(cmd(probe, "-v", "error", "-select_streams", "v:0", "-read_intervals", "%+#30",
                "-show_entries", "frame=side_data_list", "-print_format", "json", path));
        try {
            Pipeline.Info p = parse(new JSONObject(json));
            p.hdr10plus = frames.contains("2094-40");
            return p;
        } catch (JSONException e) {
            throw new IOException("Could not read ffprobe output", e);
        }
    }

    private static Pipeline.Info parse(JSONObject root) throws JSONException, IOException {
        Pipeline.Info p = new Pipeline.Info();
        JSONArray streams = root.getJSONArray("streams");
        boolean video = false;
        for (int i = 0; i < streams.length(); i++) {
            JSONObject s = streams.getJSONObject(i);
            String type = s.optString("codec_type");
            if (type.equals("video") && !video) {
                video = true;
                p.hdr10 = "smpte2084".equals(s.optString("color_transfer"));
                JSONArray sd = s.optJSONArray("side_data_list");
                for (int j = 0; sd != null && j < sd.length(); j++) {
                    JSONObject d = sd.getJSONObject(j);
                    if (d.optString("side_data_type").contains("DOVI")) {
                        p.dvProfile = d.optInt("dv_profile");
                        p.dvCompat = d.optInt("dv_bl_signal_compatibility_id");
                    }
                }
            } else if (type.equals("audio") || type.equals("subtitle")) {
                JSONObject tags = s.optJSONObject("tags");
                if (tags == null) tags = new JSONObject();
                p.tracks.add(new Pipeline.Track(s.getInt("index"), type, s.optString("codec_name", "unknown"),
                        s.optInt("channels", 2), tags.optString("language", "und"), tags.optString("title", "")));
            }
        }
        if (!video) throw new IOException("No video track");
        p.duration = root.getJSONObject("format").optDouble("duration", 0);
        JSONArray ch = root.optJSONArray("chapters");
        p.chapters = ch != null && ch.length() > 0;
        return p;
    }

    /** Runs ffmpeg with progress. Stored in `slot` first so Stop can kill it. */
    static void ffmpeg(Context ctx, List<String> args, double duration, Progress progress, Process[] slot)
            throws IOException, InterruptedException {
        List<String> c = cmd(bin(ctx, "ffmpeg"));
        c.addAll(args.subList(0, args.size() - 1));
        c.addAll(Arrays.asList("-progress", "pipe:1", "-nostats", "-loglevel", "error"));
        c.add(args.get(args.size() - 1));   // ffmpeg reads options only before the output path
        Process p = new ProcessBuilder(c).start();
        slot[0] = p;
        StringBuilder err = drain(p.getErrorStream());
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
            for (String l; (l = r.readLine()) != null; ) {
                if (l.startsWith("out_time_us=") && duration > 0) {
                    try {
                        progress.percent(Math.min(99, Long.parseLong(l.substring(12)) / 1e6 / duration * 100));
                    } catch (NumberFormatException ignored) {   // ffmpeg prints N/A before the first packet
                    }
                }
            }
        }
        int code = p.waitFor();
        slot[0] = null;
        if (code != 0) throw new IOException("ffmpeg failed: " + tail(err));
    }
}
