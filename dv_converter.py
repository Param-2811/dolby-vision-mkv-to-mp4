"""DV MKV -> MP4 converter. Lossless remux, no video re-encode.
Flow (community-proven): ffprobe -> [dovi_tool P7->8.1] -> [MP4Box tag] -> ffmpeg final mux -> verify.
"""
import json, os, re, shutil, subprocess, sys, threading, queue, glob
import tkinter as tk
import tkinter.font
from tkinter import ttk, filedialog, messagebox
try:                                        # optional: enables dropping files onto the window
    from tkinterdnd2 import TkinterDnD, DND_FILES
    TkBase = TkinterDnD.Tk
except ImportError:
    TkBase, DND_FILES = tk.Tk, None

HERE = os.path.dirname(sys.executable if getattr(sys, "frozen", False) else os.path.abspath(__file__))
TOOL_DIRS = [os.path.join(HERE, "tools"), os.path.join(HERE, "tools", "gpac"), r"C:\Tools\video", r"C:\Program Files\GPAC", r"C:\Program Files\MediaInfo"]
TOOL_DIRS += glob.glob(os.path.join(os.environ.get("LOCALAPPDATA", ""), r"Microsoft\WinGet\Packages\*\*\bin"))
TOOL_DIRS += glob.glob(os.path.join(os.environ.get("LOCALAPPDATA", ""), r"Microsoft\WinGet\Packages\*"))
os.environ["PATH"] = os.pathsep.join(TOOL_DIRS) + os.pathsep + os.environ["PATH"]
NOWIN = {"creationflags": 0x08000000} if os.name == "nt" else {}  # CREATE_NO_WINDOW

COPY_AUDIO = {"eac3", "ac3", "aac"}
TEXT_SUBS = {"subrip", "srt", "ass", "ssa", "text", "mov_text", "webvtt"}
DEVICES = ["LG TV (webOS)", "Apple (iPhone / iPad / Apple TV)", "Android / Android TV", "Generic"]
DEVICE_NOTES = {  # from community research; the file produced is the same, only the playback advice differs
    DEVICES[0]: "LG: play via the built-in Media Player app or Plex. DV 8.1 MP4 + EAC3 direct-plays on C1 and newer.",
    DEVICES[1]: "Apple: native player supports DV 5 / 8.4 only. For 8.1 files use Infuse (full DV). Plex on Apple TV shows HDR10 fallback.",
    DEVICES[2]: "Android: DV 8.1 MP4 direct-plays on Shield / Google TV / DV phones. Use Just Player or Plex if the stock player ignores DV.",
    DEVICES[3]: "Generic: DV 8.1 with HDR10 fallback is the safest choice for unknown players.",
}


def tool(name):
    return shutil.which(name)


def run(cmd, log=None, **kw):
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", **NOWIN, **kw)
    if p.returncode != 0:
        if log: log(p.stderr[-2000:])
        raise RuntimeError(f"{os.path.basename(cmd[0])} failed (exit {p.returncode})")
    return p.stdout


def probe(path):
    out = run(["ffprobe", "-v", "error", "-print_format", "json", "-show_streams", "-show_format", "-show_chapters", path])
    info = json.loads(out)
    v = next(s for s in info["streams"] if s["codec_type"] == "video")
    dovi = next((sd for sd in v.get("side_data_list", []) if "DOVI" in sd.get("side_data_type", "")), None)
    # HDR10+ lives in per-frame SEI; peek at the first frames only
    frames = run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-read_intervals", "%+#30",
                  "-show_entries", "frame=side_data_list", "-print_format", "json", path])
    hdr10plus = "2094-40" in frames
    return {
        "video": v, "streams": info["streams"], "duration": float(info["format"].get("duration", 0) or 0),
        "chapters": bool(info.get("chapters")),
        "hdr10": v.get("color_transfer") == "smpte2084",
        "hdr10plus": hdr10plus,
        "dv_profile": dovi and dovi.get("dv_profile"),
        "dv_compat": dovi and dovi.get("dv_bl_signal_compatibility_id"),
        "dv_el": bool(dovi and dovi.get("el_present_flag")),
    }


def dv_label(p):
    if not p["dv_profile"]: return "none"
    return f"{p['dv_profile']}.{p['dv_compat']}" if p["dv_profile"] in (7, 8) else str(p["dv_profile"])


class Cancelled(Exception): pass


class Job:
    procs = []      # live child processes, killed by App.stop()
    stop = False

    def __init__(self, src, outdir, device, convert_audio, subs, skip, log, progress):
        self.src, self.outdir, self.device = src, outdir, device
        self.convert_audio, self.subs, self.skip, self.log, self.progress = convert_audio, subs, skip, log, progress
        self.warn = []
        self.tmp = os.path.join(outdir, "_temp")

    def popen(self, *a, **k):
        if Job.stop: raise Cancelled()
        p = subprocess.Popen(*a, **k); Job.procs.append(p); return p

    def ffmpeg_progress(self, cmd, duration):
        p = self.popen(cmd + ["-progress", "pipe:1", "-nostats", "-loglevel", "error"],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace", **NOWIN)
        for line in p.stdout:
            if line.startswith("out_time_us=") and duration:
                try: self.progress(min(99, int(line.split("=")[1]) / 1e6 / duration * 100))
                except ValueError: pass
        err = p.stderr.read()
        if p.wait() != 0:
            if Job.stop: raise Cancelled()
            self.log(err[-2000:]); raise RuntimeError("ffmpeg failed")

    def run(self):
        name = os.path.splitext(os.path.basename(self.src))[0]
        dst = os.path.join(self.outdir, name + ".mp4")
        self.log(f"\n=== {os.path.basename(self.src)}")
        p = probe(self.src)
        src_dv = dv_label(p)
        self.log(f"Source: DV profile {src_dv} | HDR10={'yes' if p['hdr10'] else 'no'} | HDR10+={'yes' if p['hdr10plus'] else 'no'}")
        os.makedirs(self.tmp, exist_ok=True)
        try:
            video_in, tag, target = self.plan_video(p, name)
            cmd = self.build_mux(p, video_in, tag, dst)
            self.log("Muxing final MP4...")
            self.ffmpeg_progress(cmd, p["duration"])
            self.verify(dst, target)
        finally:
            shutil.rmtree(self.tmp, ignore_errors=True)
        self.progress(100)
        for w in self.warn: self.log("WARNING: " + w)
        self.log("NOTE: " + DEVICE_NOTES.get(self.device, ""))
        self.log(f"Done -> {dst}")

    # ---- decision table -------------------------------------------------
    def plan_video(self, p, name):
        prof = p["dv_profile"]
        if p["hdr10plus"] and prof:
            self.warn.append("Source has both HDR10+ and Dolby Vision. Both are kept, but MP4 players handle this inconsistently.")
        if not prof:
            self.log("No Dolby Vision: simple remux."); return self.src, "hvc1", "none (HDR10)" if p["hdr10"] else "none (SDR)"
        if prof == 7:
            self.log("Profile 7 (Blu-ray) -> converting RPU to Profile 8.1, dropping enhancement layer...")
            self.progress(0)
            self.warn.append("Profile 7 EL discarded. If it was a FEL disc, some highlight detail is lost (HDR10 base layer is intact).")
            return self.convert_p7(name), "hvc1", "8.1"
        if prof == 5:
            self.warn.append("Profile 5 kept as-is. No HDR10 fallback: the player MUST support DV Profile 5 or colors will be wrong (green/purple).")
            return self.src, "dvh1", "5"
        self.log(f"Profile {dv_label(p)} kept, direct remux."); return self.src, "hvc1", dv_label(p)

    def convert_p7(self, name):
        hevc = os.path.join(self.tmp, name + ".hevc")
        ff = self.popen(["ffmpeg", "-v", "error", "-i", self.src, "-map", "0:v:0", "-c:v", "copy",
                         "-bsf:v", "hevc_mp4toannexb", "-f", "hevc", "-"], stdout=subprocess.PIPE, **NOWIN)
        dt = self.popen(["dovi_tool", "-m", "2", "convert", "--discard", "-", "-o", hevc],
                        stdin=ff.stdout, stderr=subprocess.PIPE, text=True, errors="replace", **NOWIN)
        err = dt.stderr.read(); dt.wait()
        ff.stdout.close(); ff.wait()
        if dt.returncode != 0 or not os.path.exists(hevc):
            if Job.stop: raise Cancelled()
            self.log(err[-2000:]); raise RuntimeError("dovi_tool failed")
        # community-verified tagging path (LG C2 / Apple TV): MP4Box for video only, ffmpeg for the final mux
        mp4 = os.path.join(self.tmp, name + ".video.mp4")
        self.log("Tagging DV 8.1 with MP4Box...")
        mb = self.popen(["MP4Box", "-add", f"{hevc}:dvp=8.1:hdr=none", "-brand", "mp42isom", "-ab", "dby1", "-no-iod",
                         "-tmp", self.tmp, "-new", mp4], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors="replace", **NOWIN)
        _, err = mb.communicate()
        if mb.returncode != 0:
            if Job.stop: raise Cancelled()
            self.log(err[-2000:]); raise RuntimeError("MP4Box failed")
        os.remove(hevc)
        return mp4

    def build_mux(self, p, video_in, tag, dst):
        cmd = ["ffmpeg", "-y", "-i", video_in]
        if video_in != self.src: cmd += ["-i", self.src]
        a_src = 0 if video_in == self.src else 1
        cmd += ["-map", "0:v:0", "-c:v", "copy", "-tag:v", tag, "-strict", "unofficial",
                "-metadata:s:v:0", "handler_name=Video"]   # MP4Box leaves its import string here otherwise
        ai = 0
        for s in (s for s in p["streams"] if s["codec_type"] == "audio" and s["index"] not in self.skip):
            cmd += ["-map", f"{a_src}:{s['index']}"]
            lang = s.get("tags", {}).get("language", "und")
            ch = int(s.get("channels", 2))
            if s["codec_name"] in COPY_AUDIO or not self.convert_audio:
                cmd += [f"-c:a:{ai}", "copy"]
                if s["codec_name"] not in COPY_AUDIO:
                    self.warn.append(f"Audio '{s['codec_name']}' copied as-is; may not play in MP4.")
            else:
                cmd += [f"-c:a:{ai}", "eac3", f"-b:a:{ai}", "768k" if ch > 2 else "256k"]
                if ch > 6: cmd += [f"-ac:a:{ai}", "6"]  # ffmpeg eac3 encoder caps at 5.1
                self.log(f"Audio {ai} ({s['codec_name']} {ch}ch, {lang}) -> EAC3")
            cmd += [f"-metadata:s:a:{ai}", f"language={lang}"]
            if s.get("tags", {}).get("title"): cmd += [f"-metadata:s:a:{ai}", f"title={s['tags']['title']}"]
            ai += 1
        si = 0
        for s in (s for s in p["streams"] if s["codec_type"] == "subtitle" and s["index"] not in self.skip):
            if not self.subs: break
            if s["codec_name"] in TEXT_SUBS:
                cmd += ["-map", f"{a_src}:{s['index']}", f"-c:s:{si}", "mov_text",
                        f"-metadata:s:s:{si}", f"language={s.get('tags', {}).get('language', 'und')}"]
                if s.get("tags", {}).get("title"): cmd += [f"-metadata:s:s:{si}", f"title={s['tags']['title']}"]
                si += 1
            else:
                self.warn.append(f"Bitmap subtitle ({s['codec_name']}) skipped; MP4 only supports text subs. Convert PGS to SRT if needed.")
        if p["chapters"]: cmd += ["-map_chapters", str(a_src)]
        cmd += ["-dn", "-movflags", "+faststart", dst]
        return cmd

    def verify(self, dst, target):
        q = probe(dst)
        got = dv_label(q)
        ok = got == target or target.startswith("none")
        self.log(f"Verify: DV profile in output = {got} (expected {target}) {'OK' if ok else 'MISMATCH'}")
        if tool("mediainfo"):
            mi = run(["mediainfo", "--Output=Video;%HDR_Format/String%", dst]).strip()
            self.log(f"MediaInfo: {mi or 'no HDR format reported'}")
        if not ok: self.warn.append("Output DV profile does not match expectation. Check the log.")


# ---- GUI ----------------------------------------------------------------
LANGS = {"hin": ("hin", "hindi"), "eng": ("eng", "english")}


def track_rows(path, kind):
    """[(stream index, label, searchable language text)] for every audio or subtitle stream."""
    info = json.loads(run(["ffprobe", "-v", "error", "-print_format", "json", "-show_streams", path]))
    rows = []
    for s in info["streams"]:
        if s["codec_type"] != kind: continue
        t = s.get("tags", {})
        lang = t.get("language", "und")
        bits = [s["codec_name"], lang]
        if kind == "audio": bits.append(f"{s.get('channels', '?')}ch")
        if t.get("title"): bits.append(t["title"])
        rows.append((s["index"], "  ".join(bits), lang + " " + t.get("title", "").lower()))
    return rows


def is_lang(row, code):
    """Match the language tag, falling back to the track title (some rips tag everything 'und')."""
    tag, word = LANGS[code]
    return row[2].split(" ", 1)[0] == tag or word in row[2]


class App(TkBase):
    def __init__(self):
        super().__init__()
        try: __import__("ctypes").windll.shcore.SetProcessDpiAwareness(1)  # crisp on HiDPI
        except Exception: pass
        self.title("Dolby Vision MKV -> MP4")
        self.geometry("1200x900"); self.minsize(900, 750)
        self.tk.call("tk", "scaling", 1.6)
        for fnt in ("TkDefaultFont", "TkTextFont", "TkMenuFont", "TkHeadingFont"):
            tk.font.nametofont(fnt).configure(size=11)
        ttk.Style().configure(".", font=("Segoe UI", 11))
        self.files, self.q = [], queue.Queue()
        self.tracks = {}          # (file, kind) -> [(stream index, label, language text)]
        self.keep = {}            # (file, kind) -> set of stream indexes to keep
        pad = {"padx": 8, "pady": 4}

        top = ttk.Frame(self); top.pack(fill="x", **pad)
        ttk.Button(top, text="Add MKV files...", command=self.add_files).pack(side="left")
        ttk.Button(top, text="Add folder...", command=self.add_folder).pack(side="left", padx=4)
        ttk.Button(top, text="Clear", command=self.clear).pack(side="left")
        ttk.Label(top, text=("   Drag files or folders here" if DND_FILES else
                             "   (drag-and-drop needs: pip install tkinterdnd2)")).pack(side="left")
        self.tools_lbl = ttk.Label(top, text=""); self.tools_lbl.pack(side="right")
        self.lst = tk.Listbox(self, height=4, exportselection=False, selectmode="extended")
        self.lst.pack(fill="x", **pad)
        self.lst.bind("<<ListboxSelect>>", self.show_tracks)
        if DND_FILES:
            self.lst.drop_target_register(DND_FILES)
            self.lst.dnd_bind("<<Drop>>", self.on_drop)

        panes = ttk.Frame(self); panes.pack(fill="x", **pad)
        self.boxes = {}
        PRESETS = {
            "audio": [("All", lambda r: True), ("None", lambda r: False),
                      ("Hindi only", lambda r: is_lang(r, "hin")),
                      ("English only", lambda r: is_lang(r, "eng"))],
            "subtitle": [("All", lambda r: True), ("None", lambda r: False),
                         ("Hindi + English", lambda r: is_lang(r, "hin") or is_lang(r, "eng")),
                         ("English only", lambda r: is_lang(r, "eng")),
                         ("Hindi only", lambda r: is_lang(r, "hin"))],
        }
        for kind, title in (("audio", "Audio tracks"), ("subtitle", "Subtitle tracks")):
            fr = ttk.LabelFrame(panes, text=title + "  (buttons apply to every selected file)")
            fr.pack(side="left", fill="both", expand=True, padx=(0, 6) if kind == "audio" else (6, 0))
            tb = ttk.Frame(fr); tb.pack(fill="x", pady=2)
            for label, pred in PRESETS[kind]:
                ttk.Button(tb, text=label, command=lambda k=kind, p=pred: self.select_tracks(k, p)).pack(side="left", padx=2)
            box = tk.Listbox(fr, selectmode="multiple", height=8, exportselection=False, activestyle="none",
                             selectbackground="#2e7d32", selectforeground="white")
            sb = ttk.Scrollbar(fr, orient="vertical", command=box.yview)
            box.config(yscrollcommand=sb.set)
            sb.pack(side="right", fill="y"); box.pack(fill="both", expand=True, padx=4, pady=2)
            box.bind("<<ListboxSelect>>", lambda _e, k=kind: self.remember_tracks(k))
            self.boxes[kind] = box

        row = ttk.Frame(self); row.pack(fill="x", **pad)
        ttk.Label(row, text="Output folder:").pack(side="left")
        self.out = tk.StringVar()
        ttk.Entry(row, textvariable=self.out).pack(side="left", fill="x", expand=True, padx=4)
        ttk.Button(row, text="Browse...", command=self.pick_out).pack(side="left")

        opt = ttk.LabelFrame(self, text="Target device"); opt.pack(fill="x", **pad)
        self.device = tk.StringVar(value=DEVICES[0])
        for d in DEVICES: ttk.Radiobutton(opt, text=d, value=d, variable=self.device).pack(side="left", padx=6)

        opt2 = ttk.Frame(self); opt2.pack(fill="x", **pad)
        self.conv_audio = tk.BooleanVar(value=True); self.subs = tk.BooleanVar(value=True)
        ttk.Checkbutton(opt2, text="Convert TrueHD / DTS / FLAC audio to EAC3 (recommended for MP4)", variable=self.conv_audio).pack(anchor="w")
        ttk.Checkbutton(opt2, text="Include text subtitles (PGS/VobSub are always skipped)", variable=self.subs).pack(anchor="w")

        bar2 = ttk.Frame(self); bar2.pack(fill="x", padx=8)
        ttk.Button(bar2, text="Select all files", command=self.select_all_files).pack(side="left")
        ttk.Button(bar2, text="Copy these track choices to ALL files", command=self.copy_to_all).pack(side="left", padx=6)
        self.sel_lbl = ttk.Label(bar2, text=""); self.sel_lbl.pack(side="left", padx=6)

        act = ttk.Frame(self); act.pack(fill="x", **pad)
        self.go = ttk.Button(act, text="Convert", command=self.start); self.go.pack(side="left")
        ttk.Button(act, text="Stop", command=self.stop).pack(side="left", padx=4)
        ttk.Button(act, text="Open output folder", command=lambda: self.out.get() and os.startfile(self.out.get())).pack(side="left", padx=4)
        self.bar = ttk.Progressbar(act, maximum=100); self.bar.pack(side="left", fill="x", expand=True, padx=8)
        self.status = ttk.Label(act, text=""); self.status.pack(side="right")

        self.txt = tk.Text(self, wrap="word", state="disabled", font=("Consolas", 11)); self.txt.pack(fill="both", expand=True, **pad)
        self.check_tools(); self.after(100, self.pump)

    def check_tools(self):
        missing = [t for t in ("ffmpeg", "ffprobe", "dovi_tool", "MP4Box") if not tool(t)]
        opt = [t for t in ("mediainfo",) if not tool(t)]
        self.tools_lbl.config(text=("MISSING: " + ", ".join(missing)) if missing else "Tools OK" + (f" (optional missing: {', '.join(opt)})" if opt else ""),
                              foreground="red" if missing else "green")
        if missing:
            self.log("Missing required tools: " + ", ".join(missing) + "\nPut the .exe files in the 'tools' folder next to this app, then restart.")
        return not missing

    def add_files(self):
        for f in filedialog.askopenfilenames(filetypes=[("MKV / video", "*.mkv *.mp4 *.ts *.m2ts"), ("All", "*.*")]): self._add(f)

    def add_folder(self):
        d = filedialog.askdirectory()
        if d:
            for f in sorted(glob.glob(os.path.join(d, "*.mkv"))): self._add(f)

    def _add(self, f):
        if f not in self.files:
            self.files.append(f); self.lst.insert("end", f)
            for kind in ("audio", "subtitle"):
                try: self.tracks[f, kind] = track_rows(f, kind)
                except Exception as e: self.tracks[f, kind] = []; self.log(f"Could not read {kind} tracks of {f}: {e}")
                self.keep[f, kind] = {r[0] for r in self.tracks[f, kind]}
            if not self.out.get(): self.out.set(os.path.join(os.path.dirname(f), "MP4"))
            if len(self.files) == 1: self.lst.selection_set(0)
            self.show_tracks()

    def current_file(self):
        """The file whose tracks are shown: the first of the selection."""
        sel = self.lst.curselection()
        return self.files[sel[0]] if sel else None

    def selected_files(self):
        return [self.files[n] for n in self.lst.curselection()]

    def on_drop(self, event):
        for path in self.tk.splitlist(event.data):
            if os.path.isdir(path):
                for f in sorted(glob.glob(os.path.join(path, "*.mkv"))): self._add(f)
            elif path.lower().endswith((".mkv", ".mp4", ".ts", ".m2ts")):
                self._add(path)
            else:
                self.log(f"Ignored (not a video): {path}")

    def select_all_files(self):
        self.lst.selection_set(0, "end"); self.show_tracks()

    def show_tracks(self, _=None):
        f = self.current_file()
        n = len(self.lst.curselection())
        self.sel_lbl.config(text=(f"{n} files selected - showing: {os.path.basename(f)}" if n > 1 else
                                  (os.path.basename(f) if f else "no file selected")))
        for kind, box in self.boxes.items():
            box.delete(0, "end")
            if not f: continue
            for n, row in enumerate(self.tracks.get((f, kind), [])):
                box.insert("end", row[1])
                if row[0] in self.keep[f, kind]: box.selection_set(n)

    def remember_tracks(self, kind):
        f = self.current_file()
        if f: self.keep[f, kind] = {self.tracks[f, kind][n][0] for n in self.boxes[kind].curselection()}

    def select_tracks(self, kind, pred):
        """Apply a language rule to every selected file - track numbers differ per file,
        so the rule is matched against each file's own tracks."""
        files = self.selected_files()
        if not files: return
        for f in files:
            self.keep[f, kind] = {r[0] for r in self.tracks[f, kind] if pred(r)}
        if len(files) > 1: self.log(f"Applied {kind} choice to {len(files)} files.")
        self.show_tracks()

    def copy_to_all(self):
        """Take the shown file's kept languages and apply the same languages to every file."""
        src = self.current_file()
        if not src: return messagebox.showinfo("Select a file", "Click a file first.")
        for kind in ("audio", "subtitle"):
            langs = {r[2].split(" ", 1)[0] for r in self.tracks[src, kind] if r[0] in self.keep[src, kind]}
            for f in self.files:
                self.keep[f, kind] = {r[0] for r in self.tracks[f, kind] if r[2].split(" ", 1)[0] in langs}
        self.log(f"Copied track languages from {os.path.basename(src)} to all {len(self.files)} files.")
        self.show_tracks()

    def skip_for(self, f):
        return {r[0] for kind in ("audio", "subtitle") for r in self.tracks.get((f, kind), [])
                if r[0] not in self.keep.get((f, kind), set())}

    def clear(self): self.files.clear(); self.tracks.clear(); self.keep.clear(); self.lst.delete(0, "end"); self.show_tracks()

    def stop(self):
        Job.stop = True
        for p in Job.procs:
            try: p.kill()
            except Exception: pass
        self.log("STOP requested - killing current conversion...")
    def pick_out(self):
        d = filedialog.askdirectory()
        if d: self.out.set(d)

    def log(self, msg): self.q.put(("log", msg))
    def progress(self, v): self.q.put(("bar", v))

    def pump(self):
        try:
            while True:
                k, v = self.q.get_nowait()
                if k == "log":
                    self.txt.config(state="normal"); self.txt.insert("end", v + "\n"); self.txt.see("end"); self.txt.config(state="disabled")
                elif k == "bar": self.bar["value"] = v
                elif k == "done": self.go.config(state="normal"); self.status.config(text=v)
        except queue.Empty: pass
        try: self.after(100, self.pump)
        except tk.TclError: pass        # window closed

    def start(self):
        if not self.files: return messagebox.showinfo("Nothing to do", "Add some MKV files first.")
        if not self.check_tools(): return
        outdir = self.out.get().strip()
        if not outdir: return messagebox.showinfo("Output", "Pick an output folder.")
        os.makedirs(outdir, exist_ok=True)
        todo = []
        for f in self.files:
            dst = os.path.join(outdir, os.path.splitext(os.path.basename(f))[0] + ".mp4")
            if os.path.exists(dst) and not messagebox.askyesno("Overwrite?", f"{dst}\nalready exists. Overwrite it?"):
                self.log(f"Skipped (exists): {dst}"); continue
            todo.append(f)
        Job.stop = False; Job.procs.clear()
        self.go.config(state="disabled")
        threading.Thread(target=self.work, args=(todo, outdir), daemon=True).start()

    def work(self, todo, outdir):
        ok = 0
        for i, f in enumerate(todo, 1):
            self.q.put(("done", f"{i}/{len(todo)}")); self.go.config(state="disabled")
            try:
                Job(f, outdir, self.device.get(), self.conv_audio.get(), self.subs.get(), self.skip_for(f), self.log, self.progress).run(); ok += 1
            except Cancelled:
                self.log("Stopped."); shutil.rmtree(os.path.join(outdir, "_temp"), ignore_errors=True)
                dst = os.path.join(outdir, os.path.splitext(os.path.basename(f))[0] + ".mp4")
                if os.path.exists(dst): os.remove(dst)   # half-written file is useless
                break
            except Exception as e:
                self.log(f"FAILED: {e}")
        self.q.put(("done", f"Finished: {ok}/{len(todo)} OK"))


if __name__ == "__main__":
    App().mainloop()
