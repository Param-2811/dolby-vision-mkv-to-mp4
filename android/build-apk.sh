#!/bin/sh
# Builds the APK with the SDK tools directly. Run from Git Bash, after android/native/build-tools.sh.
# Calls d8 and apksigner through java because their .bat wrappers break on the space in this path.
set -eu
cd "$(dirname "$0")"
TOOLS=../tools/android
JDK=$(ls -d "$TOOLS"/jdk/*)
PATH="$PWD/$JDK/bin:$PATH"
BT=$TOOLS/sdk/build-tools/35.0.0
JAR=$TOOLS/sdk/platforms/android-35/android.jar
KEY=$TOOLS/dvconverter.keystore
PASS=$TOOLS/signing-password.txt   # private, like the keystore: tools/ is gitignored
OUT=build
SRC=app/src/main

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/stage/lib/arm64-v8a"

[ -f "$PASS" ] || python -c "import secrets; print(secrets.token_urlsafe(32), end='')" > "$PASS"
[ -f "$KEY" ] || keytool -genkeypair -keystore "$KEY" -storepass:file "$PASS" -keypass:file "$PASS" \
  -alias dv -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Param Patel"

"$BT/aapt2.exe" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$BT/aapt2.exe" link -I "$JAR" --manifest "$SRC/AndroidManifest.xml" --java "$OUT/gen" \
  -o "$OUT/base.apk" "$OUT/res.zip"

javac -encoding UTF-8 -nowarn -Xlint:-options -source 11 -target 11 -cp "$JAR" -d "$OUT/classes" \
  $SRC/java/com/param/dvconverter/*.java "$OUT"/gen/com/param/dvconverter/R.java
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 33 --lib "$JAR" \
  --output "$OUT/stage" $(find "$OUT/classes" -name '*.class')

cp "$SRC"/jniLibs/arm64-v8a/*.so "$OUT/stage/lib/arm64-v8a/"
python - "$OUT/base.apk" "$OUT/stage" <<'E'
import os, sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED) as z:
    for d, _, fs in os.walk(sys.argv[2]):
        for f in fs:
            p = os.path.join(d, f)
            z.write(p, os.path.relpath(p, sys.argv[2]).replace(os.sep, "/"))
E
"$BT/zipalign.exe" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
java -jar "$BT/lib/apksigner.jar" sign --ks "$KEY" --ks-pass "file:$PASS" \
  --out "$OUT/DV-Converter-1.1.2-arm64.apk" "$OUT/aligned.apk"
rm -f "$OUT"/*.idsig
ls -la "$OUT"/*.apk
