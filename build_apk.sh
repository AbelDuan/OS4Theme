#!/usr/bin/env bash
# 离线手动构建 OS4Theme APK（不依赖 gradle/AGP）：
#   aapt2 编译资源 → javac 编译 → d8 生成 dex → 塞入 META-INF/xposed → zipalign → apksigner
#
# 依赖（本机已解包，见 README 或 OS4Theme/NOTES.md）：
#   JAVA_HOME=/tmp/tools/jdk/root/usr/lib/jvm/java-17-openjdk-arm64
#   SDK=/tmp/tools/sdk/android（build-tools;34.0.0 + platforms;android-34）
set -euo pipefail

SDK=${SDK:-/tmp/tools/sdk/android}
JAVA_HOME=${JAVA_HOME:-/tmp/tools/jdk/root/usr/lib/jvm/java-17-openjdk-arm64}
BT=$SDK/build-tools/34.0.0
ANDROID_JAR=$SDK/platforms/android-34/android.jar
ROOT=$(cd "$(dirname "$0")" && pwd)
SRC=$ROOT/app/src/main
XPJAR=$ROOT/app/libs/libxposed-api-102.jar
OUT=${OUT:-$ROOT/build}
VER=$(sed -n 's/^version=//p' "$SRC/resources/META-INF/xposed/module.prop")
VCODE=$(sed -n 's/^versionCode=//p' "$SRC/resources/META-INF/xposed/module.prop")

export PATH="$JAVA_HOME/bin:$PATH"
command -v javac >/dev/null || { echo "javac 不可用：检查 JAVA_HOME=$JAVA_HOME" >&2; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "缺少 android.jar：$ANDROID_JAR" >&2; exit 1; }
[ -f "$BT/aapt2" ] || { echo "缺少 build-tools：$BT" >&2; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex" "$OUT/gen" "$ROOT/dist"

echo "== 1/6 aapt2 compile =="
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "== 2/6 aapt2 link =="
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
    --manifest "$SRC/AndroidManifest.xml" -R "$OUT/res.zip" \
    --java "$OUT/gen" \
    --min-sdk-version 33 --target-sdk-version 34 \
    --version-code "$VCODE" --version-name "$VER"

echo "== 3/6 javac =="
javac -encoding UTF-8 -source 17 -target 17 -nowarn \
    -cp "$ANDROID_JAR:$XPJAR" -d "$OUT/classes" \
    $(find "$SRC/java" "$OUT/gen" -name '*.java')

echo "== 4/6 d8 =="
# d8 需要 --lib（bootclasspath）与 --classpath（编译期依赖，运行时由 LSPosed 提供）
"$BT/d8" --release --min-api 33 --lib "$ANDROID_JAR" --classpath "$XPJAR" \
    --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "== 5/6 组装 APK =="
cp -f "$OUT/base.apk" "$OUT/app-unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/app-unsigned.apk" classes.dex )
( cd "$SRC/resources" && zip -q -X -r "$OUT/app-unsigned.apk" META-INF )
"$BT/zipalign" -f -p 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "== 6/6 签名 =="
KS=${KS:-$OUT/os4theme.jks}
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias os4theme -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass android -keypass android \
        -dname "CN=OS4 Themer,O=AbelDuan,C=CN"
fi
APK="$ROOT/dist/OS4Theme-v$VER.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$APK" "$OUT/app-aligned.apk"
"$BT/apksigner" verify --print-certs "$APK" | head -6

echo
echo "产物：$APK"
unzip -l "$APK" | grep -E "classes.dex|META-INF/xposed|AndroidManifest" || true
