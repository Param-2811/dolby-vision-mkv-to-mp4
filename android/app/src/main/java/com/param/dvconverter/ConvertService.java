package com.param.dvconverter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Foreground service, so a long remux survives the screen going off. The UI polls the static state. */
public class ConvertService extends Service {
    private static final String STOP = "stop";
    private static final int WORK = 1, RESULT = 2;

    static final class Job {
        final File src; final Pipeline.Info info; final Set<Integer> skip;
        Job(File src, Pipeline.Info info, Set<Integer> skip) { this.src = src; this.info = info; this.skip = skip; }
    }

    static List<Job> queue = new ArrayList<>();
    static boolean convertAudio = true, subs = true;

    static volatile boolean running, stop;
    static volatile int percent, position, total, savedCount, failedCount;
    static volatile String currentName = "", lastSaved;
    static final StringBuffer log = new StringBuffer();
    static final Map<String, String> state = new ConcurrentHashMap<>();
    static final Map<String, Uri> saved = new ConcurrentHashMap<>();
    private static final Process[] proc = new Process[1];

    static void stopNow() {
        stop = true;
        Process p = proc[0];
        if (p != null) p.destroyForcibly();
    }

    private static void log(String s) { log.append(s).append('\n'); }

    @Override public IBinder onBind(Intent i) { return null; }

    private Notification.Builder note() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "work").setSmallIcon(R.drawable.ic_movie).setContentIntent(open);
    }

    private Notification workNote() {
        PendingIntent halt = PendingIntent.getService(this, 1, new Intent(this, ConvertService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE);
        String title = total > 1 ? "Converting " + position + " of " + total : "Converting to MP4";
        return note().setContentTitle(title).setContentText(currentName).setSubText(percent + "%")
                .setProgress(100, percent, false).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Stop", halt).build())
                .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopNow();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("work", "Conversion", NotificationManager.IMPORTANCE_LOW));
        nm.cancel(RESULT);
        startForeground(WORK, workNote(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (running) return START_NOT_STICKY;
        running = true;
        stop = false;
        PowerManager.WakeLock lock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dvconverter:work");
        lock.acquire();
        List<Job> jobs = queue;
        new Thread(() -> {
            try {
                work(jobs);
            } finally {
                lock.release();
                running = false;
                percent = 0;
                stopForeground(STOP_FOREGROUND_REMOVE);
                announce(nm, jobs.size());
                stopSelf();
            }
        }).start();
        return START_NOT_STICKY;
    }

    private void announce(NotificationManager nm, int count) {
        if (stop) return;
        String title = failedCount > 0 ? failedCount + " of " + count + " couldn't be converted"
                : savedCount == 1 ? "Video converted" : savedCount + " videos converted";
        nm.notify(RESULT, note().setContentTitle(title).setContentText("Saved in the MP4 folder next to the original")
                .setAutoCancel(true).build());
    }

    private void work(List<Job> jobs) {
        position = 0;
        total = jobs.size();
        savedCount = 0;
        failedCount = 0;
        lastSaved = null;
        for (Job j : jobs) {
            if (stop) break;
            position++;
            currentName = j.src.getName();
            state.put(j.src.getPath(), "converting");
            percent = 0;
            try {
                convert(j);
                savedCount++;
                lastSaved = j.src.getPath();
                state.put(j.src.getPath(), "done");
            } catch (InterruptedException | IOException e) {
                if (stop) {
                    log("Stopped.");
                    state.put(j.src.getPath(), "stopped");
                    break;
                }
                failedCount++;
                log("FAILED: " + e.getMessage());
                state.put(j.src.getPath(), "failed");
            }
        }
        state.values().removeIf("waiting"::equals);
    }

    /** Never overwrites: an existing MP4 may be the user's only copy, and there is no dialog behind a service. */
    private static File freeName(File dir, String base) {
        File f = new File(dir, base + ".mp4");
        for (int n = 1; f.exists(); n++) f = new File(dir, base + " (" + n + ").mp4");
        return f;
    }

    private void showPercent(int v) {
        if (v == percent) return;
        percent = v;
        getSystemService(NotificationManager.class).notify(WORK, workNote());
    }

    private void convert(Job j) throws IOException, InterruptedException {
        log("\n=== " + j.src.getName());
        log("Source: DV profile " + Pipeline.dvLabel(j.info));
        File outdir = new File(j.src.getParentFile(), "MP4");
        if (!outdir.isDirectory() && !outdir.mkdirs()) throw new IOException("Cannot create " + outdir);
        if (outdir.getUsableSpace() < j.src.length()) throw new IOException("Not enough free space for " + j.src.getName());
        String base = j.src.getName().replaceFirst("\\.[^.]+$", "");
        File dst = freeName(outdir, base);
        File part = new File(outdir, dst.getName().replaceFirst("\\.mp4$", "") + ".part.mp4");

        List<String> warn = new ArrayList<>();
        List<String> args = Pipeline.buildMux(j.info, j.src.getPath(), part.getPath(), j.skip, convertAudio, subs, warn);
        boolean kept = false;
        try {
            log("Muxing final MP4...");
            Tools.ffmpeg(this, args, j.info.duration, v -> showPercent((int) v), proc);
            if (stop) throw new InterruptedException();
            String got = Pipeline.dvLabel(Tools.probe(this, part.getPath()));
            String want = Pipeline.dvLabel(j.info);
            log("Verify: DV profile in output = " + got + " (expected " + want + ") " + (got.equals(want) ? "OK" : "MISMATCH"));
            if (!got.equals(want)) warn.add("Output DV profile does not match expectation. Check the log.");
            if (!part.renameTo(dst)) throw new IOException("Could not rename " + part);
            kept = true;
        } finally {
            if (!kept) part.delete();   // a half-written file is useless
        }
        // Waited for so the Play action exists by the time the UI reports the file as saved.
        CountDownLatch scanned = new CountDownLatch(1);
        MediaScannerConnection.scanFile(this, new String[]{dst.getPath()}, new String[]{"video/mp4"}, (path, uri) -> {
            if (uri != null) saved.put(j.src.getPath(), uri);
            scanned.countDown();
        });
        scanned.await(5, TimeUnit.SECONDS);
        showPercent(100);
        for (String w : warn) log("WARNING: " + w);
        log("Done -> " + dst);
    }

    /** Tracks the user unticked, in the form buildMux takes. */
    static Set<Integer> skipOf(Pipeline.Info p, Set<Integer> keep) {
        Set<Integer> skip = new HashSet<>();
        for (Pipeline.Track t : p.tracks) if (!keep.contains(t.index)) skip.add(t.index);
        return skip;
    }
}
