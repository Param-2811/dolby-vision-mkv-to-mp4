#!/bin/sh
# Runs the mux-decision self-check on the desktop JDK.
set -eu
cd "$(dirname "$0")"
JDK=$(ls -d ../tools/android/jdk/*)
PATH="$PWD/$JDK/bin:$PATH"
OUT=build/check
mkdir -p "$OUT"
javac -encoding UTF-8 -d "$OUT" app/src/main/java/com/param/dvconverter/Pipeline.java test/PipelineCheck.java
java -ea -cp "$OUT" com.param.dvconverter.PipelineCheck
