# Dolby Vision MKV -> MP4 (LG TV / Apple / Android)

Remuxes Dolby Vision MKV files to MP4 that LG webOS TVs, Apple devices and Android TV
actually play. Profile 7 is converted to Profile 8.1. **The video is never re-encoded**,
originals are never touched, output goes to a separate folder.

## Install (Windows 10/11, 64-bit)

1. Download **DV-Converter-1.0.0-setup.exe** from [Releases](../../releases/latest).
2. Run it. No admin password: it installs for your user only (you can switch to
   "all users" in the first dialog if you want it machine-wide).
3. Start it from the Start menu, or tick the desktop shortcut during setup.

ffmpeg, MP4Box, dovi_tool and hdr10plus_tool are installed alongside it, in a `tools`
folder next to the app - nothing else is touched and nothing is downloaded at runtime.
To remove it: Settings - Apps - DV Converter, or the uninstaller in the install folder.

Windows SmartScreen will warn the first time, because the installer is not code-signed
(a certificate costs a few hundred dollars a year). Click **More info - Run anyway**.

Then: add MKV files, pick a target device, press **Convert**.
**Stop** cancels a running job (or run `Stop DV Converter.bat` from outside).

## Run from source

```
pip install tkinterdnd2
python dv_converter.py
```
Tools are looked up in `tools\`, `tools\gpac\`, `C:\Program Files\GPAC`, the winget
package folders and `PATH` - in that order. `winget install Gyan.FFmpeg GPAC.GPAC` covers
most of it; drop `dovi_tool.exe` and `hdr10plus_tool.exe` into `tools\`.

## Build it yourself

```
powershell -ExecutionPolicy Bypass -File build.ps1
```
Downloads any missing tool into `tools\`, builds the exe with PyInstaller, and compiles
`DV-Converter-1.0.0-setup.exe` with [Inno Setup](https://jrsoftware.org/isinfo.php)
(installed via winget if you don't have it). Needs Python 3.10+ and internet on the first run.
`dist\DV Converter\` is left behind as a portable copy if you prefer that over the installer.

Files can be **dragged and dropped** onto the window (folders too - their .mkv files are added).

## The file list

Every file you add is probed once and shown as a row:

| FILE | SOURCE | OUTPUT | AUDIO | SUBTITLES | STATUS |
|------|--------|--------|-------|-----------|--------|
| Lanterns.2024.mkv | DV 7.6 | DV 8.1 | 1 of 3 | 2 of 8 | ready |

**SOURCE** is what the file is now, **OUTPUT** is what it will become, and **STATUS** follows
each file through the run: waiting, converting, done, failed.

## Picking tracks

Select one or more files and the **Audio** and **Subtitles** panels fill up. A ticked box means
the track is kept; click a row (or press Space) to drop it. Each panel has quick picks:

- Audio: All / None / **Hindi** / English
- Subtitles: All / None / **Hindi + English** / Hindi / English

**Batch:** select several files (or press *Select all*) and one click on a quick pick applies
that choice to every selected file. Track numbers differ between releases, so the choice is
matched by language in each file, not by position.
*Apply these choices to all files* takes the languages ticked on the shown file and applies the
same languages everywhere.

Choices are remembered per file. Track titles (e.g. "American English [SDH]") carry into the MP4.
The log lives behind **Show log** at the bottom and opens by itself when something fails.

## What it does (decision table)

| Source                          | Output MP4                                                  |
|---------------------------------|-------------------------------------------------------------|
| DV Profile 7 (UHD Blu-ray)      | Converted to **DV 8.1** (RPU converted, EL dropped, MP4Box-tagged) |
| DV Profile 8.1 / 8.2 / 8.4       | Kept as-is, direct remux                                    |
| DV Profile 5                    | Kept as-is (tagged `dvh1`), warning: player must support P5 |
| HDR10 / HDR10+ only             | Plain remux                                                 |
| HDR10+ **and** DV               | Both kept, warning that players are inconsistent            |
| TrueHD / DTS / FLAC / PCM audio | Converted to EAC3 (5.1, 768k); EAC3/AC3/AAC copied           |
| SRT / ASS subtitles             | Converted to MP4 text subs (mov_text)                       |
| PGS / VobSub subtitles          | Skipped with a warning                                      |

Every output is verified with ffprobe (and MediaInfo if installed) and the DV profile is printed in the log.

## Device notes (from community research, Sep 2026)

- **LG webOS**: native player + Plex want MP4 with DV 5 / 8.1 / 8.4 and EAC3/AC3/AAC audio. Profile 7 must be converted. Jellyfin/Kodi on the TV can play DV MKV directly, if you'd rather not convert.
- **Apple (iPhone/iPad/Apple TV)**: native player supports DV 5, 8.4 (and 10.4 on newer). For 8.1 files use **Infuse** (plays 8.1 as full DV). Plex on Apple TV falls back to HDR10 for profile 8.
- **Android / Android TV**: DV 8.1 MP4 direct-plays on most DV-capable devices (Shield, Google TV, phones with DV).
- Profile 7 **FEL** discs: the enhancement layer is dropped. Picture is still full HDR10 + DV metadata, but the extra 12-bit detail is lost. Community tools (dovi_convert, DoVi_Scripts) do the same.
- TrueHD Atmos -> EAC3 loses the Atmos objects (ffmpeg limitation). Plain 5.1 is kept.

## Tools

The app shows **Tools OK** or **MISSING: ...** in the top right. If something is missing,
drop that `.exe` into the `tools` folder next to the app and restart.
Required: `ffmpeg`, `ffprobe`, `dovi_tool`, `MP4Box`. Optional: `hdr10plus_tool`, `mediainfo`.
Licenses of the bundled tools: see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## Sources
- r/ffmpeg "Convert DV Profile 7 to 8.1 using dovi_tool, mp4box and ffmpeg" (DonNETRiNO's LG C2 flow) - the pipeline used here
- r/jellyfin "I developed a tool to convert Dolby Vision Profile 7 to 8.1" (dovi_convert, FEL caveats)
- r/LGOLED "Guide - Playing Dolby Vision / Atmos on LG tv", "G4 webOS25 update enabled Media Player support for DV MKV"
- r/PleX "Dolby Vision profile 5 or 8 when picking content", "Fix: DV Profile 5 green tint"
- github.com/quietvoid/dovi_tool, github.com/nekno/DV7toDV8, github.com/R3S3t9999/DoVi_Scripts

## License

MIT (see [LICENSE](LICENSE)). The release ZIP also contains third-party tools under their
own licenses - see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

Issues and pull requests welcome.
