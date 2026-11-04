#!/bin/sh
# Cross-compiles the phone's tools as static arm64 binaries. Run inside Alpine (WSL).
# Output goes to android/app/src/main/jniLibs/arm64-v8a named lib*.so, because Android 10+
# only lets an app exec files from its own native library directory.
set -eu
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
W=/b
mkdir -p "$W/bin" "$OUT"
apk add -q build-base make pkgconf zig llvm curl tar xz bash perl file
cd "$W"
[ -f ffmpeg.tar.xz ] || curl -fsSL -o ffmpeg.tar.xz https://ffmpeg.org/releases/ffmpeg-8.1.tar.xz

cat > bin/acc <<'X'
#!/bin/sh
exec zig cc -target aarch64-linux-musl -fno-sanitize=undefined "$@"
X
cat > bin/acxx <<'X'
#!/bin/sh
exec zig c++ -target aarch64-linux-musl -fno-sanitize=undefined "$@"
X
chmod +x bin/acc bin/acxx
PATH=$W/bin:$PATH

# Remux only (DV Profile 5 and 8, no Profile 7): HEVC copy, HDR10+ probing, audio to EAC3, text subs to mov_text.
[ -d ffmpeg-8.1 ] || tar xf ffmpeg.tar.xz
cd ffmpeg-8.1
./configure --enable-cross-compile --arch=aarch64 --target-os=linux \
  --cc=acc --cxx=acxx --ld=acc --ar=llvm-ar --ranlib=llvm-ranlib --nm=llvm-nm --strip=llvm-strip \
  --pkg-config=false --extra-ldflags=-static --enable-static --disable-shared \
  --disable-autodetect --disable-doc --disable-debug --disable-network --disable-ffplay --disable-everything \
  --enable-protocol=file,pipe --enable-demuxer=matroska,mov,hevc --enable-muxer=mp4,mov,hevc,null \
  --enable-parser=hevc,h264,av1,vp9,aac,ac3,dca,mlp,flac,opus,vorbis,mpegaudio \
  --enable-bsf=hevc_mp4toannexb,h264_mp4toannexb,extract_extradata,aac_adtstoasc,dovi_rpu \
  --enable-decoder=hevc,h264,aac,aac_latm,ac3,eac3,dca,truehd,mlp,flac,alac,opus,vorbis,mp2,mp3 \
  --enable-decoder=pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_f32le,pcm_bluray,pcm_dvd \
  --enable-decoder=subrip,srt,ass,ssa,text,webvtt,movtext \
  --enable-encoder=eac3,movtext \
  --enable-filter=aresample,aformat,anull,null,format,abuffer,abuffersink,buffer,buffersink
grep -E "^WARNING|Unknown" ffbuild/config.log || true
make -j"$(nproc)" ffmpeg ffprobe
llvm-strip -o "$OUT/libffmpeg.so" ffmpeg
llvm-strip -o "$OUT/libffprobe.so" ffprobe
cd "$W"
ls -la "$OUT"
file "$OUT"/*
