#!/bin/bash
# Build and sign the widget APK straight from the SDK build-tools. No Gradle: the
# app has no dependencies, so aapt2 -> javac -> d8 -> apksigner is the whole chain.
set -e

SDK=/mnt/wdata/git/android-studio-2025.2.2.7-linux/sdk
TOOLS=$SDK/build-tools/35.0.0
PLATFORM=$SDK/platforms/android-35/android.jar
KEYSTORE=/mnt/wdata/git/tinyapps/signAppsKeyStore.jks

# Never hardcode the keystore password -- this file is committed. Fail before
# doing any work rather than at the signing step.
: "${KS_PASS:?run as: KS_PASS=<keystore password> ./build.sh}"

# d8 and apksigner are shell wrappers that exec a bare "java", so the JBR has to
# be on PATH rather than just referenced by absolute path.
export JAVA_HOME=/mnt/wdata/git/android-studio-2025.2.2.7-linux/android-studio/jbr
export ANDROID_HOME=$SDK
export PATH=$JAVA_HOME/bin:$PATH

HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$HERE/build

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$TOOLS/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"
"$TOOLS/aapt2" link -o "$OUT/unsigned.apk" -I "$PLATFORM" --manifest "$HERE/AndroidManifest.xml" --java "$OUT/gen" --min-sdk-version 29 --target-sdk-version 35 "$OUT/res.zip"
javac --release 11 -nowarn -g:none -classpath "$PLATFORM" -d "$OUT/classes" $(find "$HERE/java" "$OUT/gen" -name '*.java')
# d8 defaults to --debug, which keeps line tables and local variable names.
"$TOOLS/d8" --release --lib "$PLATFORM" --min-api 29 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)

"$TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass env:KS_PASS --ks-key-alias jacoblo --key-pass env:KS_PASS --out "$OUT/bulb-release.apk" "$OUT/aligned.apk"

echo "built $OUT/bulb-release.apk"
