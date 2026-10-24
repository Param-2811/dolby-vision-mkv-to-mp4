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
# Paper-utility surface: warm neutral paper, ink-blue accent, 1px rules instead of
# boxes-inside-boxes. Colours live here and nowhere else.
PAPER, CARD, RAIL = "#F7F6F2", "#FFFFFF", "#EFEDE7"
LINE, LINE_HI = "#DEDBD2", "#C8C4B8"
INK, BODY, MUTED = "#1B1A17", "#36332D", "#6C685E"
ACCENT, ACCENT_HI, ACCENT_LO = "#27487F", "#1E3A68", "#16294A"
KEEP, ALERT, WARN = "#2F6B4F", "#95291F", "#7A5312"
SEL = "#E4EAF6"

UI, UI_MED, SMALL = ("Segoe UI", 10), ("Segoe UI Semibold", 10), ("Segoe UI", 9)
LABEL, TITLE, MONO = ("Segoe UI Semibold", 8), ("Segoe UI Semibold", 16), ("Consolas", 9)
ROW_H = 28

LANGS = {"hin": ("hin", "hindi"), "eng": ("eng", "english")}


def track_rows(streams, kind):
    """[(stream index, label, searchable language text)] for every audio or subtitle stream."""
    rows = []
    for s in streams:
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


def source_label(p):
    if p["dv_profile"]:
        return f"DV {dv_label(p)}" + (" + HDR10+" if p["hdr10plus"] else "")
    return "HDR10+" if p["hdr10plus"] else "HDR10" if p["hdr10"] else "SDR"


def output_label(p):
    """What this file will become - the decision table, shown before the user presses Convert."""
    prof = p["dv_profile"]
    if not prof: return "HDR10" if p["hdr10"] else "SDR"
    if prof == 7: return "DV 8.1"
    if prof == 5: return "DV 5"
    return f"DV {dv_label(p)}"


def checkbox_image(on, gap=0):
    """A 14px checkbox drawn pixel by pixel: no icon font, no image files to ship.
    `gap` adds empty pixels on the right so it can sit next to a label."""
    n = 14
    img = tk.PhotoImage(width=n + gap, height=n)
    edge = ACCENT if on else LINE_HI
    img.put(edge, to=(1, 1, n - 1, 2)); img.put(edge, to=(1, n - 2, n - 1, n - 1))
    img.put(edge, to=(1, 1, 2, n - 1)); img.put(edge, to=(n - 2, 1, n - 1, n - 1))
    if on:
        img.put(ACCENT, to=(2, 2, n - 2, n - 2))
        for x, y in ((3, 6), (4, 7), (5, 8), (6, 7), (7, 6), (8, 5), (9, 4), (10, 3)):
            img.put("#FFFFFF", to=(x, y, x + 2, y + 2))
    return img


def rule(parent):
    """A 1px horizontal rule - the only separator this design uses."""
    return tk.Frame(parent, height=1, bg=LINE)


def framed(parent, bg=CARD):
    """Content on a card with a hairline border, built from frames so the line stays 1px."""
    outer = tk.Frame(parent, bg=LINE)
    inner = tk.Frame(outer, bg=bg)
    inner.pack(fill="both", expand=True, padx=1, pady=1)
    return outer, inner


class App(TkBase):
    def __init__(self):
        super().__init__()
        try: __import__("ctypes").windll.shcore.SetProcessDpiAwareness(1)  # crisp on HiDPI
        except Exception: pass
        try: __import__("ctypes").windll.kernel32.CreateMutexW(None, False, "DVConverterRunning")
        except Exception: pass    # the installer looks for this to tell the user to close the app
        try: self.iconbitmap(os.path.join(HERE, "icon.ico"))
        except Exception: pass    # running from a checkout without the icon is fine
        self.title("Dolby Vision to MP4")
        sw, sh = self.winfo_screenwidth(), self.winfo_screenheight()
        self.geometry(f"{min(1240, sw - 120)}x{min(980, sh - 90)}+60+16")
        self.minsize(900, 640)
        self.configure(bg=PAPER)
        self.tk.call("tk", "scaling", 1.6)
        for fnt in ("TkDefaultFont", "TkTextFont", "TkMenuFont", "TkHeadingFont"):
            tk.font.nametofont(fnt).configure(family="Segoe UI", size=10)
        self.style_widgets()

        self.files, self.q = [], queue.Queue()
        self.info = {}            # file -> probe() result
        self.tracks = {}          # (file, kind) -> [(stream index, label, language text)]
        self.keep = {}            # (file, kind) -> set of stream indexes to keep
        self.state = {}           # file -> status word shown in the table
        self.running = False
        self.ck = {True: checkbox_image(True), False: checkbox_image(False)}
        self.ck_pad = (checkbox_image(False, 8), checkbox_image(True, 8))

        self.columnconfigure(0, weight=1)
        for r, w in ((2, 3), (3, 2), (8, 3)):   # file table, track panels and log share spare height
            self.rowconfigure(r, weight=w)
        for r in (2, 3):                        # ... and never shrink away to nothing
            self.rowconfigure(r, minsize=96)
        self.build_header()
        self.build_files()
        self.build_tracks()
        self.build_hint()
        self.build_options()
        self.build_actions()
        self.build_log()

        self.check_tools(); self.refresh(); self.after(100, self.pump)

    # ---- styling ----------------------------------------------------------
    def style_widgets(self):
        st = ttk.Style(); st.theme_use("clam")
        st.configure(".", background=PAPER, foreground=BODY, font=UI,
                     bordercolor=LINE, lightcolor=PAPER, darkcolor=PAPER, focuscolor=ACCENT)
        st.configure("TFrame", background=PAPER)
        st.configure("Card.TFrame", background=CARD)
        st.configure("Rail.TFrame", background=RAIL)
        st.configure("TLabel", background=PAPER, foreground=BODY)
        st.configure("Title.TLabel", font=TITLE, foreground=INK)
        st.configure("Section.TLabel", font=LABEL, foreground=MUTED, background=RAIL)
        st.configure("Muted.TLabel", font=SMALL, foreground=MUTED)
        st.configure("Empty.TLabel", font=UI, foreground=MUTED, background=CARD, justify="center")
        st.configure("Chip.TLabel", font=LABEL, padding=(8, 4))

        st.configure("TButton", background=CARD, foreground=BODY, bordercolor=LINE_HI,
                     lightcolor=CARD, darkcolor=CARD, relief="flat", padding=(13, 7),
                     focusthickness=1, focuscolor=ACCENT, font=UI)
        st.map("TButton",
               background=[("pressed", "#E7E4DB"), ("active", "#F3F1EB"), ("disabled", RAIL)],
               foreground=[("disabled", "#AAA59B")],
               bordercolor=[("pressed", LINE_HI), ("active", LINE_HI), ("disabled", LINE)])
        st.configure("Primary.TButton", background=ACCENT, foreground="#FFFFFF", font=UI_MED,
                     bordercolor=ACCENT, lightcolor=ACCENT, darkcolor=ACCENT, padding=(20, 9))
        st.map("Primary.TButton",
               background=[("pressed", ACCENT_LO), ("active", ACCENT_HI), ("disabled", "#C3CBDA")],
               bordercolor=[("pressed", ACCENT_LO), ("active", ACCENT_HI), ("disabled", "#C3CBDA")],
               foreground=[("disabled", "#F2F2F0")])
        st.configure("Key.TButton", foreground=ACCENT, font=UI_MED, bordercolor=LINE_HI)
        st.map("Key.TButton", foreground=[("disabled", "#AAA59B"), ("pressed", ACCENT_LO)],
               bordercolor=[("active", ACCENT), ("focus", ACCENT)])
        st.configure("Chip.TButton", background=CARD, bordercolor=LINE_HI, lightcolor=CARD,
                     darkcolor=CARD, padding=(8, 2), font=SMALL, foreground=BODY)
        st.map("Chip.TButton",
               background=[("pressed", "#E7E4DB"), ("active", "#F3F1EB"), ("disabled", RAIL)],
               foreground=[("disabled", "#AAA59B")])

        st.configure("Treeview", background=CARD, fieldbackground=CARD, foreground=BODY,
                     rowheight=ROW_H, borderwidth=0, relief="flat", font=UI)
        st.map("Treeview", background=[("selected", SEL)], foreground=[("selected", INK)])
        st.configure("Treeview.Heading", background=RAIL, foreground=MUTED, font=LABEL,
                     relief="flat", borderwidth=0, padding=(10, 7))
        st.map("Treeview.Heading", background=[("active", RAIL)])
        st.layout("Plain.Treeview", [("Treeview.treearea", {"sticky": "nswe"})])
        st.configure("Plain.Treeview", background=CARD, fieldbackground=CARD, rowheight=ROW_H)
        st.map("Plain.Treeview", background=[("selected", SEL)], foreground=[("selected", INK)])

        st.configure("TEntry", fieldbackground=CARD, bordercolor=LINE_HI, lightcolor=CARD,
                     darkcolor=CARD, insertcolor=ACCENT, padding=6, selectbackground=SEL,
                     selectforeground=INK)
        st.map("TEntry", bordercolor=[("focus", ACCENT)])
        for s in ("TRadiobutton", "TCheckbutton"):
            st.configure(s, background=PAPER, foreground=BODY, padding=(0, 4),
                         indicatorbackground=CARD, indicatorforeground=ACCENT,
                         upperbordercolor=LINE_HI, lowerbordercolor=LINE_HI)
            st.map(s, foreground=[("active", INK)],
                   indicatorbackground=[("disabled", RAIL), ("pressed", "#E7E4DB")],
                   upperbordercolor=[("selected", ACCENT), ("focus", ACCENT)],
                   lowerbordercolor=[("selected", ACCENT), ("focus", ACCENT)])
        st.layout("Flat.TCheckbutton", [("Checkbutton.padding", {"sticky": "nswe", "children": [
            ("Checkbutton.focus", {"side": "left", "sticky": "w", "children": [
                ("Checkbutton.label", {"sticky": "nswe"})]})]})])
        st.configure("Flat.TCheckbutton", background=PAPER, foreground=BODY, padding=(0, 5))
        st.map("Flat.TCheckbutton", foreground=[("active", INK)])
        st.configure("TProgressbar", background=ACCENT, troughcolor=RAIL, bordercolor=RAIL,
                     lightcolor=ACCENT, darkcolor=ACCENT, thickness=6)
        st.configure("Vertical.TScrollbar", background=LINE_HI, troughcolor=PAPER,
                     bordercolor=PAPER, lightcolor=LINE_HI, darkcolor=LINE_HI,
                     arrowcolor=MUTED, relief="flat", arrowsize=12)
        st.map("Vertical.TScrollbar", background=[("active", MUTED)])

    # ---- layout -----------------------------------------------------------
    def build_header(self):
        bar = ttk.Frame(self); bar.grid(row=0, column=0, sticky="ew", padx=24, pady=(14, 10))
        ttk.Label(bar, text="Dolby Vision to MP4", style="Title.TLabel").pack(side="left")
        self.tools_lbl = ttk.Label(bar, text="", style="Chip.TLabel")
        self.tools_lbl.pack(side="right")
        rule(self).grid(row=1, column=0, sticky="ew")

    def build_files(self):
        wrap = ttk.Frame(self); wrap.grid(row=2, column=0, sticky="nsew", padx=24, pady=(14, 0))
        bar = ttk.Frame(wrap); bar.pack(fill="x", pady=(0, 10))
        ttk.Button(bar, text="Add files", style="Key.TButton", command=self.add_files).pack(side="left")
        ttk.Button(bar, text="Add folder", command=self.add_folder).pack(side="left", padx=8)
        ttk.Button(bar, text="Clear list", command=self.clear).pack(side="left")
        ttk.Button(bar, text="Select all", command=self.select_all_files).pack(side="left", padx=8)
        self.sel_lbl = ttk.Label(bar, text="", style="Muted.TLabel"); self.sel_lbl.pack(side="right")

        outer, inner = framed(wrap); outer.pack(fill="both", expand=True)
        cols = ("source", "output", "audio", "subs", "status")
        self.tree = ttk.Treeview(inner, columns=cols, show="tree headings", selectmode="extended", height=3)
        self.tree.heading("#0", text="FILE", anchor="w")
        self.tree.column("#0", width=430, minwidth=220, stretch=True)
        for c, title, w in (("source", "SOURCE", 150), ("output", "OUTPUT", 110),
                            ("audio", "AUDIO", 85), ("subs", "SUBTITLES", 100), ("status", "STATUS", 130)):
            anchor = "center" if c in ("audio", "subs") else "w"
            self.tree.heading(c, text=title, anchor=anchor)
            self.tree.column(c, width=w, minwidth=w, stretch=False, anchor=anchor)
        sb = ttk.Scrollbar(inner, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y"); self.tree.pack(fill="both", expand=True)
        self.tree.tag_configure("working", foreground=ACCENT)
        self.tree.tag_configure("done", foreground=KEEP)
        self.tree.tag_configure("failed", foreground=ALERT)
        self.tree.bind("<<TreeviewSelect>>", lambda _e: self.refresh())

        self.empty = ttk.Label(inner, style="Empty.TLabel", justify="center", anchor="center", text=(
            "Drop MKV files or a folder here\nor use Add files" if DND_FILES else
            "Use Add files to begin\n(drag and drop needs: pip install tkinterdnd2)"))
        self.place_empty()
        if DND_FILES:
            for w in (self, self.tree):
                w.drop_target_register(DND_FILES)
                w.dnd_bind("<<Drop>>", self.on_drop)

    def build_tracks(self):
        wrap = ttk.Frame(self); wrap.grid(row=3, column=0, sticky="nsew", padx=24, pady=(14, 0))
        presets = {
            "audio": [("All", lambda r: True), ("None", lambda r: False),
                      ("Hindi", lambda r: is_lang(r, "hin")),
                      ("English", lambda r: is_lang(r, "eng"))],
            "subtitle": [("All", lambda r: True), ("None", lambda r: False),
                         ("Hindi + English", lambda r: is_lang(r, "hin") or is_lang(r, "eng")),
                         ("Hindi", lambda r: is_lang(r, "hin")),
                         ("English", lambda r: is_lang(r, "eng"))],
        }
        self.boxes = {}
        for kind, title in (("audio", "AUDIO"), ("subtitle", "SUBTITLES")):
            col = ttk.Frame(wrap)
            col.pack(side="left", fill="both", expand=True, padx=(0, 9) if kind == "audio" else (9, 0))
            outer, inner = framed(col); outer.pack(fill="both", expand=True)
            head = ttk.Frame(inner, style="Rail.TFrame"); head.pack(fill="x")
            ttk.Label(head, text=title, style="Section.TLabel").pack(side="left", padx=10, pady=7)
            for label, pred in presets[kind]:
                ttk.Button(head, text=label, style="Chip.TButton",
                           command=lambda k=kind, p=pred: self.select_tracks(k, p)).pack(side="left", padx=(0, 6), pady=5)
            tk.Frame(inner, height=1, bg=LINE).pack(fill="x")
            box = ttk.Treeview(inner, columns=("t",), show="tree", selectmode="browse",
                               style="Plain.Treeview", height=4)
            box.column("#0", width=34, minwidth=34, stretch=False, anchor="center")
            box.column("t", width=260, stretch=True)
            sb = ttk.Scrollbar(inner, orient="vertical", command=box.yview)
            box.configure(yscrollcommand=sb.set)
            sb.pack(side="right", fill="y"); box.pack(fill="both", expand=True)
            box.tag_configure("keep", foreground=BODY)
            box.tag_configure("drop", foreground=MUTED)
            box.bind("<Button-1>", lambda e, k=kind: self.click_track(e, k))
            box.bind("<space>", lambda _e, k=kind: self.toggle_focused(k))
            box.bind("<Return>", lambda _e, k=kind: self.toggle_focused(k))
            self.boxes[kind] = box

    def build_options(self):
        rule(self).grid(row=5, column=0, sticky="ew", pady=(14, 0))
        wrap = ttk.Frame(self); wrap.grid(row=6, column=0, sticky="ew", padx=24, pady=(12, 0))

        dev = ttk.Frame(wrap); dev.pack(fill="x")
        ttk.Label(dev, text="Play on", style="Muted.TLabel").pack(side="left", padx=(0, 14))
        self.device = tk.StringVar(value=DEVICES[0])
        for d in DEVICES:
            ttk.Radiobutton(dev, text=d, value=d, variable=self.device,
                            command=self.show_note).pack(side="left", padx=(0, 18))
        self.note = ttk.Label(wrap, style="Muted.TLabel", wraplength=1050, justify="left")
        self.note.pack(fill="x", pady=(5, 8))

        self.conv_audio = tk.BooleanVar(value=True); self.subs = tk.BooleanVar(value=True)
        img = (self.ck_pad[0], "selected", self.ck_pad[1])
        opts = ttk.Frame(wrap); opts.pack(fill="x")
        for var, text in ((self.conv_audio, "Convert TrueHD, DTS and FLAC audio to EAC3"),
                          (self.subs, "Include text subtitles")):
            ttk.Checkbutton(opts, variable=var, text=text, style="Flat.TCheckbutton",
                            image=img, compound="left").pack(side="left", padx=(0, 28))

        out = ttk.Frame(wrap); out.pack(fill="x", pady=(10, 0))
        ttk.Label(out, text="Save to", style="Muted.TLabel").pack(side="left", padx=(0, 14))
        self.out = tk.StringVar()
        ttk.Entry(out, textvariable=self.out).pack(side="left", fill="x", expand=True)
        ttk.Button(out, text="Browse", command=self.pick_out).pack(side="left", padx=(8, 0))
        self.open_btn = ttk.Button(out, text="Open", command=self.open_out)
        self.open_btn.pack(side="left", padx=(8, 0))
        self.show_note()

    def build_hint(self):
        hint = ttk.Frame(self); hint.grid(row=4, column=0, sticky="ew", padx=24, pady=(8, 0))
        ttk.Label(hint, style="Muted.TLabel", text="Click a row to keep or drop it. "
                  "The buttons above apply to every file selected in the list, matched by language."
                  ).pack(side="left")
        ttk.Button(hint, text="Apply these choices to all files", style="Chip.TButton",
                   command=self.copy_to_all).pack(side="right")

    def build_actions(self):
        """Packed from the bottom so the primary action keeps its place on a short screen."""
        wrap = ttk.Frame(self); wrap.grid(row=7, column=0, sticky="ew", padx=24, pady=(10, 12))
        self.go = ttk.Button(wrap, text="Convert", style="Primary.TButton", command=self.start)
        self.go.pack(side="left")
        self.stop_btn = ttk.Button(wrap, text="Stop", command=self.stop, state="disabled")
        self.stop_btn.pack(side="left", padx=10)
        self.log_btn = ttk.Button(wrap, text="Show log", style="Chip.TButton", command=self.toggle_log)
        self.log_btn.pack(side="right")
        self.status = ttk.Label(wrap, text="", style="Muted.TLabel")
        self.status.pack(side="right", padx=(0, 16))
        self.bar = ttk.Progressbar(wrap, maximum=100)
        self.bar.pack(side="left", fill="x", expand=True, padx=20)

    def build_log(self):
        self.log_wrap = ttk.Frame(self)
        self.log_wrap.grid(row=8, column=0, sticky="nsew", padx=24, pady=(0, 14))
        self.log_wrap.grid_remove()
        outer, inner = framed(self.log_wrap, bg=CARD); outer.pack(fill="both", expand=True)
        self.txt = tk.Text(inner, wrap="word", state="disabled", font=MONO, height=5,
                           bg=CARD, fg=BODY, relief="flat", padx=12, pady=10, borderwidth=0,
                           insertbackground=ACCENT, selectbackground=SEL, selectforeground=INK)
        sb = ttk.Scrollbar(inner, orient="vertical", command=self.txt.yview)
        self.txt.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y"); self.txt.pack(fill="both", expand=True)
        self.txt.tag_configure("warn", foreground=WARN)
        self.txt.tag_configure("bad", foreground=ALERT)
        self.txt.tag_configure("good", foreground=KEEP)
        self.log_open = False

    def toggle_log(self, show=None):
        self.log_open = (not self.log_open) if show is None else show
        # reserve real height for the drawer; the panels above it shrink, they can scroll
        self.rowconfigure(8, minsize=170 if self.log_open else 0)
        if self.log_open: self.log_wrap.grid()
        else: self.log_wrap.grid_remove()
        self.log_btn.config(text="Hide log" if self.log_open else "Show log")

    def show_note(self):
        self.note.config(text=DEVICE_NOTES[self.device.get()])

    # ---- tools ------------------------------------------------------------
    def check_tools(self):
        missing = [t for t in ("ffmpeg", "ffprobe", "dovi_tool", "MP4Box") if not tool(t)]
        opt = [t for t in ("hdr10plus_tool", "mediainfo") if not tool(t)]
        if missing:
            self.tools_lbl.config(text="Missing: " + ", ".join(missing), foreground=ALERT, background="#F6E7E4")
            self.log("Missing required tools: " + ", ".join(missing) +
                     "\nPut the .exe files in the 'tools' folder next to this app, then restart.")
            self.toggle_log(True)
        else:
            text = "All tools ready" if not opt else "Tools ready, optional missing: " + ", ".join(opt)
            self.tools_lbl.config(text=text, foreground=KEEP, background="#E6EFE9")
        return not missing

    # ---- file list --------------------------------------------------------
    def add_files(self):
        for f in filedialog.askopenfilenames(
                filetypes=[("MKV / video", "*.mkv *.mp4 *.ts *.m2ts"), ("All files", "*.*")]):
            self._add(f)

    def add_folder(self):
        d = filedialog.askdirectory()
        if d:
            for f in sorted(glob.glob(os.path.join(d, "*.mkv"))): self._add(f)

    def _add(self, f):
        if f in self.files: return
        try:
            p = probe(f)
        except Exception as e:
            self.log(f"Could not read {os.path.basename(f)}: {e}"); return
        self.files.append(f); self.info[f] = p; self.state[f] = "ready"
        for kind in ("audio", "subtitle"):
            self.tracks[f, kind] = track_rows(p["streams"], kind)
            self.keep[f, kind] = {r[0] for r in self.tracks[f, kind]}
        self.tree.insert("", "end", iid=f, text=os.path.basename(f), values=self.row_values(f))
        if not self.out.get(): self.out.set(os.path.join(os.path.dirname(f), "MP4"))
        if len(self.files) == 1: self.tree.selection_set(f)
        self.refresh()

    def place_empty(self):
        self.empty.place(relx=0.5, rely=0.5, anchor="center", y=16)   # clear of the column headings

    def row_values(self, f):
        p, a, s = self.info[f], self.tracks[f, "audio"], self.tracks[f, "subtitle"]
        return (source_label(p), output_label(p),
                f"{len(self.keep[f, 'audio'])} of {len(a)}" if a else "none",
                f"{len(self.keep[f, 'subtitle'])} of {len(s)}" if s else "none",
                self.state.get(f, "ready"))

    def current_file(self):
        """The file whose tracks are shown: the first of the selection."""
        sel = self.tree.selection()
        return sel[0] if sel else None

    def selected_files(self):
        return list(self.tree.selection())

    def on_drop(self, event):
        for path in self.tk.splitlist(event.data):
            if os.path.isdir(path):
                for f in sorted(glob.glob(os.path.join(path, "*.mkv"))): self._add(f)
            elif path.lower().endswith((".mkv", ".mp4", ".ts", ".m2ts")):
                self._add(path)
            else:
                self.log(f"Ignored (not a video): {path}")

    def clear(self):
        if self.running: return
        self.files.clear(); self.tracks.clear(); self.keep.clear(); self.info.clear(); self.state.clear()
        self.tree.delete(*self.tree.get_children())
        self.status.config(text=""); self.bar["value"] = 0
        self.refresh()

    # ---- track choices ----------------------------------------------------
    def refresh(self, _=None):
        f, n = self.current_file(), len(self.tree.selection())
        self.sel_lbl.config(text=(
            f"{len(self.files)} files, {n} selected - showing {os.path.basename(f)}" if n > 1 else
            (os.path.basename(f) if f else "no file selected")) if self.files else "")
        self.empty.place_forget() if self.files else self.place_empty()
        for kind, box in self.boxes.items():
            box.delete(*box.get_children())
            for row in self.tracks.get((f, kind), []):
                on = row[0] in self.keep[f, kind]
                box.insert("", "end", iid=str(row[0]), image=self.ck[on], values=(row[1],),
                           tags=("keep" if on else "drop",))
        for path in self.files:
            self.tree.item(path, values=self.row_values(path))
        label = "Convert" if not self.files else (
            "Convert 1 file" if len(self.files) == 1 else f"Convert {len(self.files)} files")
        self.go.config(text=label, state="disabled" if self.running or not self.files else "normal")
        self.stop_btn.config(state="normal" if self.running else "disabled")

    def click_track(self, event, kind):
        box = self.boxes[kind]
        iid = box.identify_row(event.y)
        if iid: self.toggle_track(kind, iid)
        return "break"

    def toggle_focused(self, kind):
        iid = self.boxes[kind].focus()
        if iid: self.toggle_track(kind, iid)
        return "break"

    def toggle_track(self, kind, iid):
        f = self.current_file()
        if not f: return
        idx = int(iid)
        kept = self.keep[f, kind]
        kept.discard(idx) if idx in kept else kept.add(idx)
        self.refresh()
        box = self.boxes[kind]
        if box.exists(iid): box.focus(iid); box.selection_set(iid)

    def select_tracks(self, kind, pred):
        """Apply a language rule to every selected file - track numbers differ per file,
        so the rule is matched against each file's own tracks."""
        files = self.selected_files()
        if not files: return
        for f in files:
            self.keep[f, kind] = {r[0] for r in self.tracks[f, kind] if pred(r)}
        if len(files) > 1: self.log(f"Applied {kind} choice to {len(files)} files.")
        self.refresh()

    def select_all_files(self):
        self.tree.selection_set(self.files)
        self.refresh()

    def copy_to_all(self):
        """Take the shown file's kept languages and apply the same languages to every file."""
        src = self.current_file()
        if not src: return messagebox.showinfo("Select a file", "Click a file in the list first.")
        for kind in ("audio", "subtitle"):
            langs = {r[2].split(" ", 1)[0] for r in self.tracks[src, kind] if r[0] in self.keep[src, kind]}
            for f in self.files:
                self.keep[f, kind] = {r[0] for r in self.tracks[f, kind] if r[2].split(" ", 1)[0] in langs}
        self.log(f"Copied track languages from {os.path.basename(src)} to all {len(self.files)} files.")
        self.refresh()

    def skip_for(self, f):
        return {r[0] for kind in ("audio", "subtitle") for r in self.tracks.get((f, kind), [])
                if r[0] not in self.keep.get((f, kind), set())}

    # ---- output + run -----------------------------------------------------
    def pick_out(self):
        d = filedialog.askdirectory()
        if d: self.out.set(d)

    def open_out(self):
        d = self.out.get().strip()
        if d and os.path.isdir(d): os.startfile(d)
        else: messagebox.showinfo("No folder yet", "That folder does not exist yet. Convert something first.")

    def stop(self):
        Job.stop = True
        for p in Job.procs:
            try: p.kill()
            except Exception: pass
        self.log("Stop requested - killing the running conversion.")

    def log(self, msg): self.q.put(("log", msg))
    def progress(self, v): self.q.put(("bar", v))

    def pump(self):
        try:
            while True:
                k, v = self.q.get_nowait()
                if k == "log":
                    tag = ("bad" if v.startswith(("FAILED", "Missing")) else
                           "warn" if v.startswith("WARNING") else
                           "good" if v.startswith("Done ->") else "")
                    self.txt.config(state="normal")
                    self.txt.insert("end", v + "\n", tag)
                    self.txt.see("end"); self.txt.config(state="disabled")
                    if tag == "bad": self.toggle_log(True)
                elif k == "bar":
                    self.bar["value"] = v
                elif k == "state":
                    f, word = v
                    self.state[f] = word
                    if f in self.files:
                        self.tree.item(f, values=self.row_values(f),
                                       tags=("working" if word == "converting" else
                                             "done" if word == "done" else
                                             "failed" if word in ("failed", "stopped") else ""))
                elif k == "status":
                    self.status.config(text=v)
                elif k == "done":
                    self.running = False; self.status.config(text=v); self.bar["value"] = 0
                    self.refresh()
        except queue.Empty: pass
        try: self.after(100, self.pump)
        except tk.TclError: pass        # window closed

    def start(self):
        if not self.files: return messagebox.showinfo("Nothing to do", "Add some MKV files first.")
        if not self.check_tools(): return
        outdir = self.out.get().strip()
        if not outdir: return messagebox.showinfo("Where to?", "Pick a folder to save the MP4 files in.")
        os.makedirs(outdir, exist_ok=True)
        todo = []
        for f in self.files:
            dst = os.path.join(outdir, os.path.splitext(os.path.basename(f))[0] + ".mp4")
            if os.path.exists(dst) and not messagebox.askyesno("Overwrite?", f"{dst}\nalready exists. Overwrite it?"):
                self.log(f"Skipped (exists): {dst}")
                self.q.put(("state", (f, "skipped")))
                continue
            todo.append(f)
        if not todo: return
        Job.stop = False; Job.procs.clear()
        self.running = True; self.refresh(); self.toggle_log(True)
        for f in todo: self.q.put(("state", (f, "waiting")))
        # Tk variables must not be read from the worker thread
        opts = (self.device.get(), self.conv_audio.get(), self.subs.get())
        threading.Thread(target=self.work, args=(todo, outdir, opts), daemon=True).start()

    def work(self, todo, outdir, opts):
        ok = 0
        for i, f in enumerate(todo, 1):
            self.q.put(("status", f"{i} of {len(todo)} - {os.path.basename(f)}"))
            self.q.put(("state", (f, "converting")))
            try:
                Job(f, outdir, *opts, self.skip_for(f), self.log, self.progress).run()
                ok += 1; self.q.put(("state", (f, "done")))
            except Cancelled:
                self.log("Stopped."); shutil.rmtree(os.path.join(outdir, "_temp"), ignore_errors=True)
                dst = os.path.join(outdir, os.path.splitext(os.path.basename(f))[0] + ".mp4")
                if os.path.exists(dst): os.remove(dst)   # half-written file is useless
                self.q.put(("state", (f, "stopped")))
                break
            except Exception as e:
                self.log(f"FAILED: {e}"); self.q.put(("state", (f, "failed")))
        self.q.put(("done", f"Finished - {ok} of {len(todo)} converted"))


if __name__ == "__main__":
    App().mainloop()
