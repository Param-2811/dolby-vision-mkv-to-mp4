# Third-party tools

DV Converter itself is MIT (see `LICENSE`). It does not contain any of the code below -
it runs these programs as separate processes. The release ZIP ships them unmodified,
under their own licenses:

| Tool | License | Source |
|------|---------|--------|
| ffmpeg / ffprobe (BtbN win64 gpl-shared build) | GPL v3 | https://github.com/BtbN/FFmpeg-Builds - source: https://github.com/FFmpeg/FFmpeg |
| MP4Box + libgpac (GPAC) | LGPL v2.1+ | https://github.com/gpac/gpac |
| dovi_tool | MIT | https://github.com/quietvoid/dovi_tool |
| hdr10plus_tool | MIT | https://github.com/quietvoid/hdr10plus_tool |
| MediaInfo CLI (optional, not bundled) | BSD-2-Clause | https://github.com/MediaArea/MediaInfo |

The ffmpeg build is GPL v3, so the ZIP as a whole is distributed under GPL v3 terms;
its complete source is at the links above. Nothing in this repository is GPL -
`build.ps1` downloads those binaries at build time.

## Android app

The APK contains its own ffmpeg and ffprobe, which the app also runs as separate processes.
They are built from the unmodified ffmpeg 8.1 source by `android/native/build-tools.sh`,
without GPL components, and that script holds the exact configuration.

| Component | License | Source |
|-----------|---------|--------|
| ffmpeg / ffprobe 8.1 | LGPL v2.1+ | https://ffmpeg.org/releases/ffmpeg-8.1.tar.xz |
| musl libc (linked statically) | MIT | https://musl.libc.org |
| Material Icons | Apache 2.0 | https://github.com/google/material-design-icons |
