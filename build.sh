#!/usr/bin/env bash
# 图片转 GIF —— 不依赖 Gradle 的手工打包脚本
#
# 只用 aapt2 / javac / d8 / apksigner 四步把一个 WebView 壳打成 APK，
# 适合在手机上的 Termux 里跑（没有 Android Studio 也能出包）。
#
# 用法：
#   ./build.sh            # 出 debug 签名的 dist/gif-maker-<ver>.apk
#   VERSION_NAME=1.1 VERSION_CODE=2 ./build.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

VERSION_NAME="${VERSION_NAME:-1.0}"
VERSION_CODE="${VERSION_CODE:-1}"
MIN_SDK="${MIN_SDK:-24}"
TARGET_SDK="${TARGET_SDK:-34}"
PKG="com.dsharness.gifmaker"

# ---- 工具链 -----------------------------------------------------------------
JAVA_HOME="${JAVA_HOME:-$HOME/android-env/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

ANDROID_SDK="${ANDROID_SDK:-$HOME/android-sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_SDK/build-tools/37.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_SDK/platforms/android-37/android.jar}"
# build-tools 里的 aapt2 是 x86_64 主机版，手机上用 android-env 里的 aarch64 版
AAPT2="${AAPT2:-$HOME/android-env/data/data/com.termux/files/usr/bin/aapt2}"

D8="$BUILD_TOOLS/d8"
APKSIGNER="$BUILD_TOOLS/apksigner"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"

BUILD="$ROOT/build"
DIST="$ROOT/dist"
OUT_APK="$DIST/gif-maker-$VERSION_NAME.apk"

for f in "$AAPT2" "$D8" "$APKSIGNER" "$ANDROID_JAR" "$KEYSTORE"; do
    [[ -e "$f" ]] || { echo "缺少工具：$f" >&2; exit 1; }
done

echo "== 1/6 同步网页与图标 =="
# web/index.html 是唯一真源，每次构建同步进 assets，避免两处漂移
# web/index.html 是唯一真源，每次构建同步进 assets，避免两处漂移
# （源文件是只读的，cp 会连权限一起带过来，这里显式放开）
install -m 644 web/index.html android/assets/index.html
python3 scripts/make-icons.py

echo "== 2/6 编译资源 =="
rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/classes" "$DIST"
"$AAPT2" compile --dir android/res -o "$BUILD/res.zip"

echo "== 3/6 链接资源（生成壳 APK）=="
"$AAPT2" link \
    -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest android/AndroidManifest.xml \
    -A android/assets \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    "$BUILD/res.zip"

echo "== 4/6 编译 Java 并转 dex =="
find android/src -name '*.java' > "$BUILD/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
    -bootclasspath "$ANDROID_JAR" \
    -d "$BUILD/classes" \
    @"$BUILD/sources.txt"

mkdir -p "$BUILD/dex"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$D8" --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" \
    $(cat "$BUILD/classes.txt")

echo "== 5/6 合成 APK =="
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
( cd "$BUILD/dex" && zip -q -X "$BUILD/unsigned.apk" classes.dex )

# 校验 resources.arsc 未压缩且 4 字节对齐（Android 11+ 的安装要求）
python3 - "$BUILD/unsigned.apk" <<'PY'
import struct, sys, zipfile
path = sys.argv[1]
with zipfile.ZipFile(path) as z:
    info = z.getinfo("resources.arsc")
    with open(path, "rb") as fh:
        fh.seek(info.header_offset)
        header = fh.read(30)
        name_len, extra_len = struct.unpack("<HH", header[26:30])
        data_off = info.header_offset + 30 + name_len + extra_len
if info.compress_type != zipfile.ZIP_STORED:
    sys.exit("resources.arsc 被压缩了，APK 可能装不上")
if data_off % 4:
    sys.exit(f"resources.arsc 偏移 {data_off} 未 4 字节对齐，APK 可能装不上")
print(f"   resources.arsc ok（未压缩，偏移 {data_off} 对齐 4）")
PY

echo "== 6/6 签名 =="
rm -f "$OUT_APK"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KS_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --min-sdk-version "$MIN_SDK" \
    --out "$OUT_APK" \
    "$BUILD/unsigned.apk"

"$APKSIGNER" verify --print-certs "$OUT_APK" | head -6
"$AAPT2" dump badging "$OUT_APK" | head -6

echo
ls -lh "$OUT_APK"
echo "打包完成：$OUT_APK"
