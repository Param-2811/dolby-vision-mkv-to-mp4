package com.param.dvconverter;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final class Item {
        final File file;
        Pipeline.Info info;
        String error;
        Bitmap thumb;
        final Set<Integer> keep = new HashSet<>();
        Item(File f) { file = f; }
    }

    private final List<Item> items = new ArrayList<>();
    private final Set<Item> selected = new HashSet<>();
    private final ExecutorService prober = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final OnBackInvokedCallback clearSelection = this::clearSelection;
    private final Adapter adapter = new Adapter();
    private SharedPreferences prefs;
    private boolean scanning, wasRunning, backHooked;
    private int barInset, navInset;
    private Runnable hideSnack;

    private LinearLayout topBar, actionRow, overlay, empty, miniBar, snack;
    private TextView title, headline, miniTitle, miniName, miniPercent, snackText, emptyTitle, emptyBody;
    private ImageButton closeBtn, allBtn, refreshBtn, moreBtn;
    private Button fab, emptyAction, snackAction;
    private ProgressBar scanBar, miniProgress;
    private ListView list;
    private ImageView emptyIcon;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("options", MODE_PRIVATE);
        getWindow().setDecorFitsSystemWindows(false);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.surface));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(buildTopBar());
        scanBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanBar.setIndeterminate(true);
        scanBar.setIndeterminateTintList(ColorStateList.valueOf(getColor(R.color.primary)));
        scanBar.setVisibility(View.INVISIBLE);
        scanBar.setContentDescription("Looking for videos");
        column.addView(scanBar, new LinearLayout.LayoutParams(-1, dp(4)));
        FrameLayout content = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setClipToPadding(false);
        list.setAdapter(adapter);
        list.setSelector(android.R.color.transparent);
        list.setOnItemClickListener((p, v, pos, id) -> tapped(items.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> { longPressed(items.get(pos)); return true; });
        content.addView(list);
        content.addView(buildEmpty());
        column.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(column);
        root.addView(buildOverlay(), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        root.setOnApplyWindowInsetsListener((v, in) -> {
            Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            topBar.setPadding(s.left + dp(4), s.top, s.right + dp(4), 0);
            list.setPadding(s.left, 0, s.right, list.getPaddingBottom());
            overlay.setPadding(s.left + dp(12), 0, s.right + dp(12), s.bottom + dp(12));
            navInset = s.bottom;
            syncListPadding();
            return in;
        });
        overlay.addOnLayoutChangeListener((v, l, t, r, bottom, ol, ot, or, ob) -> syncListPadding());
        setContentView(root);
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        refreshChrome();
        tick();
    }

    private void syncListPadding() {
        int h = overlay.getHeight();
        if (h != barInset) {
            barInset = h;
            list.setPadding(list.getPaddingLeft(), 0, list.getPaddingRight(), Math.max(h, navInset) + dp(8));
        }
    }

    // ---- structure ---------------------------------------------------------
    private View buildTopBar() {
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        actionRow = new LinearLayout(this);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        closeBtn = Ui.icon(this, R.drawable.ic_close, R.color.on_surface, "Clear selection", v -> clearSelection());
        title = Ui.oneLine(Ui.text(this, "", R.color.on_surface, 22, false));
        title.setPadding(dp(12), 0, dp(4), 0);
        allBtn = Ui.icon(this, R.drawable.ic_select_all, R.color.on_surface_variant, "Select all", v -> selectAll());
        refreshBtn = Ui.icon(this, R.drawable.ic_refresh, R.color.on_surface_variant, "Look for videos again", v -> scan());
        moreBtn = Ui.icon(this, R.drawable.ic_more, R.color.on_surface_variant, "More options", this::menu);
        actionRow.addView(closeBtn);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1);
        title.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.addView(title, lp);
        actionRow.addView(allBtn);
        actionRow.addView(refreshBtn);
        actionRow.addView(moreBtn);
        topBar.addView(actionRow);
        // Material's large top app bar: the product name gets its own line and is never truncated.
        headline = Ui.text(this, "Dolby Vision MKV to MP4", R.color.on_surface, 28, false);
        headline.setPadding(dp(12), 0, dp(12), dp(16));
        topBar.addView(headline);
        return topBar;
    }

    private View buildEmpty() {
        empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(32), 0, dp(32), dp(64));
        emptyIcon = new ImageView(this);
        emptyIcon.setImageResource(R.drawable.ic_movie);
        emptyIcon.setImageTintList(ColorStateList.valueOf(getColor(R.color.on_secondary_container)));
        emptyIcon.setBackground(Ui.shape(this, getColor(R.color.secondary_container), 0, 48));
        emptyIcon.setPadding(dp(24), dp(24), dp(24), dp(24));
        emptyIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        empty.addView(emptyIcon, new LinearLayout.LayoutParams(dp(96), dp(96)));
        emptyTitle = Ui.text(this, "", R.color.on_surface, 22, false);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, dp(24), 0, dp(8));
        empty.addView(emptyTitle);
        emptyBody = Ui.text(this, "", R.color.on_surface_variant, 14, false);
        emptyBody.setGravity(Gravity.CENTER);
        emptyBody.setPadding(0, 0, 0, dp(24));
        empty.addView(emptyBody);
        emptyAction = Ui.filled(this, "", v -> { });
        empty.addView(emptyAction, new LinearLayout.LayoutParams(-2, -2));
        return empty;
    }

    private View buildOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);

        fab = Ui.button(this, "Convert", R.color.primary_container, R.color.on_primary_container, 0, v -> convertSelected());
        fab.setBackground(Ui.ripple(this, Ui.shape(this, getColor(R.color.primary_container), 0, 16),
                Ui.shape(this, Color.WHITE, 0, 16)));
        android.graphics.drawable.Drawable arrow = getDrawable(R.drawable.ic_arrow).mutate();
        arrow.setTint(getColor(R.color.on_primary_container));
        arrow.setBounds(0, 0, dp(24), dp(24));
        fab.setCompoundDrawablesRelative(arrow, null, null, null);
        fab.setCompoundDrawablePadding(dp(12));
        fab.setPaddingRelative(dp(16), 0, dp(20), 0);
        fab.setElevation(dp(6));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, dp(56));
        fp.gravity = Gravity.END;
        fp.setMargins(0, dp(8), dp(4), dp(12));
        overlay.addView(fab, fp);

        miniBar = new LinearLayout(this);
        miniBar.setOrientation(LinearLayout.VERTICAL);
        miniBar.setBackground(Ui.ripple(this, Ui.shape(this, getColor(R.color.surface_high), 0, 16),
                Ui.shape(this, Color.WHITE, 0, 16)));
        miniBar.setClipToOutline(true);
        miniBar.setElevation(dp(3));
        miniBar.setOnClickListener(v -> logSheet());
        LinearLayout miniRow = new LinearLayout(this);
        miniRow.setGravity(Gravity.CENTER_VERTICAL);
        miniRow.setPadding(dp(16), dp(4), dp(4), 0);
        LinearLayout miniWords = new LinearLayout(this);
        miniWords.setOrientation(LinearLayout.VERTICAL);
        miniTitle = Ui.oneLine(Ui.text(this, "", R.color.on_surface, 14, true));
        miniName = Ui.oneLine(Ui.text(this, "", R.color.on_surface_variant, 12, false));
        miniWords.addView(miniTitle);
        miniWords.addView(miniName);
        miniRow.addView(miniWords, new LinearLayout.LayoutParams(0, -2, 1));
        miniPercent = Ui.text(this, "", R.color.on_surface, 14, true);
        miniPercent.setFontFeatureSettings("tnum");
        miniPercent.setPadding(dp(12), 0, dp(4), 0);
        miniRow.addView(miniPercent);
        miniRow.addView(Ui.icon(this, R.drawable.ic_stop, R.color.on_surface, "Stop converting", v -> confirmStop()));
        miniBar.addView(miniRow);
        miniProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        miniProgress.setMax(100);
        miniProgress.setProgressTintList(ColorStateList.valueOf(getColor(R.color.primary)));
        miniProgress.setProgressBackgroundTintList(ColorStateList.valueOf(getColor(R.color.outline_variant)));
        miniBar.addView(miniProgress, new LinearLayout.LayoutParams(-1, dp(4)));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        overlay.addView(miniBar, mp);

        snack = new LinearLayout(this);
        snack.setGravity(Gravity.CENTER_VERTICAL);
        snack.setBackground(Ui.shape(this, getColor(R.color.inverse_surface), 0, 8));
        snack.setPadding(dp(16), dp(4), dp(8), dp(4));
        snack.setElevation(dp(6));
        snackText = Ui.text(this, "", R.color.inverse_on_surface, 14, false);
        snack.addView(snackText, new LinearLayout.LayoutParams(0, -2, 1));
        snackAction = Ui.button(this, "", 0, R.color.inverse_primary, 0, v -> { });
        snackAction.setPadding(dp(12), 0, dp(12), 0);
        snack.addView(snackAction);
        snack.setMinimumHeight(dp(48));
        snack.setVisibility(View.GONE);
        snack.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(8);
        overlay.addView(snack, sp);
        return overlay;
    }

    private void menu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add("Conversion settings").setOnMenuItemClickListener(i -> { settingsSheet(); return true; });
        m.getMenu().add("Activity log").setOnMenuItemClickListener(i -> { logSheet(); return true; });
        m.show();
    }

    /** Swaps the app bar between its normal and contextual selection forms, and the screen's empty states. */
    private void refreshChrome() {
        boolean picking = !selected.isEmpty();
        title.setText(picking ? selected.size() + " selected" : "");
        title.setPaddingRelative(0, 0, dp(4), 0);
        headline.setVisibility(picking ? View.GONE : View.VISIBLE);
        closeBtn.setVisibility(picking ? View.VISIBLE : View.GONE);
        allBtn.setVisibility(picking ? View.VISIBLE : View.GONE);
        refreshBtn.setVisibility(picking ? View.GONE : View.VISIBLE);
        moreBtn.setVisibility(picking ? View.GONE : View.VISIBLE);
        topBar.setBackgroundColor(getColor(picking ? R.color.secondary_container : R.color.surface));
        fab.setVisibility(picking && !ConvertService.running ? View.VISIBLE : View.GONE);
        fab.setText(selected.size() > 1 ? "Convert " + selected.size() : "Convert");
        if (picking != backHooked) {
            backHooked = picking;
            OnBackInvokedDispatcher back = getOnBackInvokedDispatcher();
            if (picking) back.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, clearSelection);
            else back.unregisterOnBackInvokedCallback(clearSelection);
        }

        boolean allowed = Environment.isExternalStorageManager();
        boolean none = items.isEmpty();
        empty.setVisibility(!allowed || (none && !scanning) ? View.VISIBLE : View.GONE);
        if (!allowed) {
            emptyTitle.setText("Find your videos");
            emptyBody.setText("Android needs to let this app open your MKV files and save the MP4 next to them.");
            emptyAction.setText("Allow access");
            emptyAction.setOnClickListener(v -> startActivity(new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))));
        } else {
            emptyTitle.setText("No MKV videos yet");
            emptyBody.setText("Copy an MKV to this phone and it will show up here.");
            emptyAction.setText("Look again");
            emptyAction.setOnClickListener(v -> scan());
        }
        refreshBtn.setEnabled(allowed);
        scanBar.setVisibility(scanning ? View.VISIBLE : View.INVISIBLE);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshChrome();
        if (Environment.isExternalStorageManager() && items.isEmpty()) scan();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        prober.shutdownNow();
    }

    // ---- videos ------------------------------------------------------------
    private void scan() {
        if (scanning || !Environment.isExternalStorageManager()) return;
        scanning = true;
        refreshChrome();
        new Thread(() -> {
            List<File> found = new ArrayList<>(), parts = new ArrayList<>();
            for (StorageVolume v : getSystemService(StorageManager.class).getStorageVolumes()) {
                if (v.getDirectory() != null) walk(v.getDirectory(), found, parts);
            }
            found.sort((x, y) -> Long.compare(y.lastModified(), x.lastModified()));
            ui.post(() -> {
                scanning = false;
                if (isDestroyed()) return;
                List<Item> old = new ArrayList<>(items);
                items.clear();
                for (File f : found) {
                    Item it = old.stream().filter(o -> o.file.equals(f)).findFirst().orElse(null);
                    if (it == null) { it = new Item(f); read(it); }
                    items.add(it);
                }
                selected.retainAll(items);
                adapter.notifyDataSetChanged();
                refreshChrome();
                offerCleanup(parts);
            });
        }).start();
    }

    /** A killed conversion cannot resume, so its file is only wasted space. The choice stays with the user. */
    private void offerCleanup(List<File> parts) {
        if (parts.isEmpty() || ConvertService.running) return;
        long bytes = 0;
        for (File f : parts) bytes += f.length();
        snack(Formatter.formatShortFileSize(this, bytes) + " in unfinished files", "Delete", () -> {
            if (ConvertService.running) return;
            for (File f : parts) f.delete();
            snack("Unfinished files deleted", null, null);
        });
    }

    private static void walk(File dir, List<File> out, List<File> parts) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (!n.startsWith(".") && !n.equals("Android")) walk(f, out, parts);
            } else if (n.toLowerCase().endsWith(".mkv")) out.add(f);
            else if (n.endsWith(".part.mp4")) parts.add(f);
        }
    }

    private void read(Item it) {
        prober.execute(() -> {
            Pipeline.Info p = null;
            String err = null;
            try {
                p = Tools.probe(this, it.file.getPath());
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                ConvertService.log.append("Could not read ").append(it.file.getName()).append(": ").append(err).append("\n");
            }
            Bitmap thumb = null;
            try {
                thumb = ThumbnailUtils.createVideoThumbnail(it.file, new Size(480, 270), null);
            } catch (Exception ignored) {   // the row falls back to the film icon
            }
            Pipeline.Info info = p;
            String error = err;
            Bitmap frame = thumb;
            ui.post(() -> {
                it.info = info;
                it.error = error;
                it.thumb = frame;
                if (info != null) for (Pipeline.Track t : info.tracks) if (!t.bitmap()) it.keep.add(t.index);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private boolean convertible(Item it) {
        return it.info != null && Pipeline.refuse(it.info) == null;
    }

    private void tapped(Item it) {
        if (!selected.isEmpty()) { toggle(it); return; }
        if (it.error != null) { logSheet(); return; }
        if (it.info == null) { snack("Still checking this video", null, null); return; }
        String refused = Pipeline.refuse(it.info);
        if (refused != null) { snack(refused, null, null); return; }
        new ConvertSheet(this, new ArrayList<>(Arrays.asList(it))).show();
    }

    private void longPressed(Item it) {
        list.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        toggle(it);
    }

    private void toggle(Item it) {
        if (selected.remove(it)) { /* unselected */ }
        else if (convertible(it)) selected.add(it);
        else snack(it.info == null ? "Still checking this video" : Pipeline.refuse(it.info), null, null);
        adapter.notifyDataSetChanged();
        refreshChrome();
    }

    private void selectAll() {
        for (Item it : items) if (convertible(it)) selected.add(it);
        adapter.notifyDataSetChanged();
        refreshChrome();
    }

    private void clearSelection() {
        selected.clear();
        adapter.notifyDataSetChanged();
        refreshChrome();
    }

    private void convertSelected() {
        List<Item> picked = new ArrayList<>();
        for (Item it : items) if (selected.contains(it)) picked.add(it);
        new ConvertSheet(this, picked).show();
    }

    boolean convertAudioOn() { return prefs.getBoolean("audio", true); }

    void convert(List<Item> picked) {
        if (ConvertService.running) { snack("Another conversion is running", null, null); return; }
        List<ConvertService.Job> jobs = new ArrayList<>();
        for (Item it : picked) {
            jobs.add(new ConvertService.Job(it.file, it.info, ConvertService.skipOf(it.info, it.keep)));
            ConvertService.state.put(it.file.getPath(), "waiting");
        }
        ConvertService.queue = jobs;
        ConvertService.convertAudio = convertAudioOn();
        ConvertService.subs = prefs.getBoolean("subs", true);
        selected.clear();
        adapter.notifyDataSetChanged();
        startForegroundService(new Intent(this, ConvertService.class));
        refreshChrome();
    }

    private void play(Uri uri) {
        startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    private void confirmStop() {
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Stop converting?")
                .setMessage("The unfinished MP4 will be deleted. Your MKV is not touched.")
                .setNegativeButton("Keep going", null)
                .setPositiveButton("Stop", (x, w) -> ConvertService.stopNow())
                .create();
        d.getWindow().setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(
                Ui.shape(this, getColor(R.color.surface_container), 0, 28), dp(24)));
        d.show();
        for (int which : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE}) {
            d.getButton(which).setAllCaps(false);
            d.getButton(which).setTextColor(getColor(R.color.primary));
        }
    }

    // ---- sheets ------------------------------------------------------------
    private void settingsSheet() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView head = Ui.text(this, "Conversion settings", R.color.on_surface, 22, false);
        head.setPadding(dp(24), 0, dp(24), dp(8));
        body.addView(head);
        body.addView(option("audio", "Convert audio to EAC3", "TrueHD, DTS and FLAC often won't play in MP4"));
        body.addView(option("subs", "Include text subtitles", "Image subtitles can't be saved in MP4"));
        View pad = new View(this);
        body.addView(pad, new LinearLayout.LayoutParams(-1, dp(24)));
        Ui.sheet(this, body, null).show();
    }

    private View option(String key, String title, String support) {
        Switch s = Ui.materialSwitch(this, prefs.getBoolean(key, true));
        return Ui.toggleRow(this, title, support, s, true, () -> {
            s.toggle();
            prefs.edit().putBoolean(key, s.isChecked()).apply();
        });
    }

    private void logSheet() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), 0, dp(24), dp(24));
        body.addView(Ui.text(this, "Activity log", R.color.on_surface, 22, false));
        String log = ConvertService.log.toString().trim();
        TextView t = Ui.text(this, log.isEmpty() ? "Nothing has been converted yet." : log, R.color.on_surface_variant, 14, false);
        t.setPadding(0, dp(12), 0, 0);
        t.setTextIsSelectable(true);
        body.addView(t);
        Dialog d = Ui.sheet(this, body, null);
        d.show();
    }

    // ---- transient feedback ------------------------------------------------
    private void snack(String message, String action, Runnable run) {
        snackText.setText(message);
        snackAction.setVisibility(action == null ? View.GONE : View.VISIBLE);
        if (action != null) {
            snackAction.setText(action);
            snackAction.setOnClickListener(v -> { snack.setVisibility(View.GONE); run.run(); });
        }
        snack.setVisibility(View.VISIBLE);
        if (hideSnack != null) ui.removeCallbacks(hideSnack);
        hideSnack = () -> snack.setVisibility(View.GONE);
        ui.postDelayed(hideSnack, action == null ? 4000 : 8000);
    }

    private void showMini(boolean show) {
        if (show == (miniBar.getVisibility() == View.VISIBLE)) return;
        miniBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            miniBar.setTranslationY(dp(96));
            ObjectAnimator in = ObjectAnimator.ofFloat(miniBar, View.TRANSLATION_Y, 0);
            in.setDuration(350);
            in.setInterpolator(new PathInterpolator(0.05f, 0.7f, 0.1f, 1f));
            in.start();
        }
    }

    /** The service keeps running without the screen, so its state is polled rather than pushed. */
    private void tick() {
        boolean run = ConvertService.running;
        showMini(run);
        if (run) {
            int total = Math.max(1, ConvertService.total);
            miniTitle.setText(total > 1 ? "Converting " + Math.max(1, ConvertService.position) + " of " + total : "Converting");
            miniName.setText(ConvertService.currentName);
            miniPercent.setText(ConvertService.percent + "%");
            miniProgress.setProgress(ConvertService.percent);
            adapter.notifyDataSetChanged();
        }
        if (run != wasRunning) {
            wasRunning = run;
            refreshChrome();
            adapter.notifyDataSetChanged();
            if (!run) finished();
        }
        ui.postDelayed(this::tick, 500);
    }

    private void finished() {
        if (ConvertService.stop) {
            snack("Stopped. The unfinished MP4 was deleted.", null, null);
        } else if (ConvertService.failedCount > 0) {
            snack(ConvertService.failedCount == 1 ? "A video couldn't be converted" : ConvertService.failedCount + " videos couldn't be converted",
                    "View log", this::logSheet);
        } else if (ConvertService.savedCount == 1 && ConvertService.lastSaved != null) {
            Uri uri = ConvertService.saved.get(ConvertService.lastSaved);
            if (uri != null) snack("Saved to the MP4 folder", "Play", () -> play(uri));
            else snack("Saved to the MP4 folder", null, null);
        } else if (ConvertService.savedCount > 1) {
            snack(ConvertService.savedCount + " videos saved to their MP4 folders", null, null);
        }
    }

    // ---- list --------------------------------------------------------------
    private final class Row {
        final LinearLayout view;
        final ImageView thumb, mark;
        final TextView name, support, badge;
        final ImageButton playBtn;

        Row() {
            view = new LinearLayout(MainActivity.this);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setMinimumHeight(dp(88));
            view.setPadding(dp(16), dp(10), dp(8), dp(10));
            FrameLayout lead = new FrameLayout(MainActivity.this);
            thumb = new ImageView(MainActivity.this);
            thumb.setClipToOutline(true);
            thumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            lead.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
            mark = new ImageView(MainActivity.this);
            mark.setImageResource(R.drawable.ic_check);
            mark.setImageTintList(ColorStateList.valueOf(getColor(R.color.on_primary)));
            mark.setBackground(Ui.shape(MainActivity.this, getColor(R.color.primary), 0, 16));
            mark.setPadding(dp(4), dp(4), dp(4), dp(4));
            lead.addView(mark, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
            view.addView(lead, new LinearLayout.LayoutParams(dp(112), dp(63)));
            LinearLayout words = new LinearLayout(MainActivity.this);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setPadding(dp(16), 0, dp(8), 0);
            name = Ui.text(MainActivity.this, "", R.color.on_surface, 16, false);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(name);
            support = Ui.text(MainActivity.this, "", R.color.on_surface_variant, 14, false);
            support.setMaxLines(2);
            words.addView(support);
            view.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            badge = Ui.text(MainActivity.this, "", R.color.on_tertiary_container, 12, true);
            badge.setBackground(Ui.shape(MainActivity.this, getColor(R.color.tertiary_container), 0, 8));
            badge.setPadding(dp(8), dp(4), dp(8), dp(4));
            badge.setMaxLines(1);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.rightMargin = dp(8);
            view.addView(badge, bp);
            playBtn = Ui.icon(MainActivity.this, R.drawable.ic_play, R.color.primary, "Play converted video", v -> { });
            playBtn.setFocusable(false);
            view.addView(playBtn);
            view.setTag(this);
        }

        void bind(Item it) {
            boolean picked = selected.contains(it);
            view.setBackground(Ui.ripple(MainActivity.this,
                    new android.graphics.drawable.ColorDrawable(picked ? getColor(R.color.secondary_container) : Color.TRANSPARENT),
                    Ui.shape(MainActivity.this, Color.WHITE, 0, 0)));
            name.setText(it.file.getName());
            thumb.setBackground(Ui.shape(MainActivity.this, getColor(R.color.surface_high), 0, 12));
            if (it.thumb != null) {
                thumb.setImageTintList(null);
                thumb.setImageBitmap(it.thumb);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setPadding(0, 0, 0, 0);
            } else {
                thumb.setImageResource(R.drawable.ic_movie);
                thumb.setImageTintList(ColorStateList.valueOf(getColor(R.color.on_surface_variant)));
                thumb.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            }
            mark.setVisibility(picked ? View.VISIBLE : View.GONE);
            thumb.setAlpha(picked ? 0.4f : 1f);

            String size = Formatter.formatShortFileSize(MainActivity.this, it.file.length());
            String run = ConvertService.state.get(it.file.getPath());
            Uri done = ConvertService.saved.get(it.file.getPath());
            boolean ok = convertible(it);
            String text;
            if (it.error != null) text = "Couldn't read this video";
            else if (it.info == null) text = "Checking…";
            else if (!ok) text = Pipeline.refuse(it.info);
            else if ("converting".equals(run)) text = "Converting";
            else if ("waiting".equals(run)) text = "Waiting";
            else if ("failed".equals(run)) text = "Failed. Open the activity log";
            else if ("stopped".equals(run)) text = "Stopped";
            else if ("done".equals(run)) text = "Saved to MP4 folder";
            else text = size + "  ·  " + Ui.duration(it.info.duration);
            support.setText(text);
            support.setTextColor(getColor(it.error != null || "failed".equals(run) ? R.color.error
                    : "done".equals(run) ? R.color.primary : R.color.on_surface_variant));
            boolean showPlay = done != null && selected.isEmpty();
            badge.setVisibility(ok && !showPlay ? View.VISIBLE : View.GONE);
            if (ok) badge.setText("DV " + Pipeline.dvLabel(it.info));
            playBtn.setVisibility(showPlay ? View.VISIBLE : View.GONE);
            if (showPlay) playBtn.setOnClickListener(v -> play(done));
            view.setContentDescription(it.file.getName() + ", " + text + (ok ? ", Dolby Vision " + Pipeline.dvLabel(it.info) : "")
                    + (picked ? ", selected" : ""));
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return items.get(p).file.getPath().hashCode(); }
        @Override public boolean hasStableIds() { return true; }

        @Override public View getView(int p, View convert, ViewGroup parent) {
            Row row = convert == null ? new Row() : (Row) convert.getTag();
            row.bind(items.get(p));
            return row.view;
        }
    }
}
