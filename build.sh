#!/usr/bin/env bash
# Збирання APK «Тека» без Android Studio і Gradle.
# Потрібно: JDK 17+, Python 3, zip, unzip, curl, git.
# Інструменти (aapt2, D8, apksig, android.jar) завантажуються автоматично в .tools/.
set -euo pipefail
cd "$(dirname "$0")"

APP=app
OUT=build
TOOLS="${TOOLS:-.tools}"
KEYSTORE="${KEYSTORE:-keystore.p12}"
STORE_PASS="${STORE_PASS:-android}"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"
BUNDLETOOL_VER=1.17.2

mkdir -p "$TOOLS"
BT="$TOOLS/bundletool.jar"
AJ="$TOOLS/android-34.jar"
AAPT2="$TOOLS/aapt2"

step() { printf '\n\033[1;33m== %s\033[0m\n' "$*"; }

# ---------- інструменти ----------
if [ ! -f "$BT" ]; then
  step "Завантаження bundletool $BUNDLETOOL_VER (aapt2, D8, apksig)"
  curl -fL --retry 3 -o "$BT" "https://github.com/google/bundletool/releases/download/$BUNDLETOOL_VER/bundletool-all-$BUNDLETOOL_VER.jar"
fi
if [ ! -x "$AAPT2" ]; then
  case "$(uname -s)" in
    Darwin) SRC=macos/aapt2 ;;
    *)      SRC=linux/aapt2 ;;
  esac
  unzip -o -q -j "$BT" "$SRC" -d "$TOOLS"
  chmod +x "$AAPT2"
fi
if [ ! -f "$AJ" ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ -f "$ANDROID_HOME/platforms/android-34/android.jar" ]; then
    cp "$ANDROID_HOME/platforms/android-34/android.jar" "$AJ"
  else
    step "Завантаження android.jar (API 34)"
    rm -rf "$TOOLS/aj"
    git clone -q --depth 1 --filter=blob:none --sparse https://github.com/Reginer/aosp-android-jar.git "$TOOLS/aj"
    git -C "$TOOLS/aj" sparse-checkout set android-34
    cp "$TOOLS/aj/android-34/android.jar" "$AJ"
    rm -rf "$TOOLS/aj"
  fi
fi

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

# ---------- ресурси ----------
step "aapt2: компіляція ресурсів"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"

step "aapt2: маніфест, ресурси, інтерфейс (assets)"
"$AAPT2" link -o "$OUT/base.apk" -I "$AJ" --manifest "$APP/AndroidManifest.xml" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -A "$APP/assets" --java "$OUT/gen" --auto-add-overlay "$OUT/res.zip"

# ---------- код ----------
step "javac"
javac -nowarn -Xlint:-options --release 8 -encoding UTF-8 -cp "$AJ" -d "$OUT/classes" \
  $(find "$APP/java" "$OUT/gen" -name '*.java')

step "D8: байт-код → classes.dex"
java -cp "$BT" com.android.tools.r8.D8 --release --min-api 24 --lib "$AJ" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

# ---------- пакування і підпис ----------
step "Пакування і вирівнювання"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -9 ../unsigned.apk classes.dex)
python3 "$APP/tools/zipalign.py" "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  step "Створення ключа підпису $KEYSTORE"
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias teka -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Teka" -storepass "$STORE_PASS" -keypass "$STORE_PASS" >/dev/null 2>&1
fi

step "Підпис (APK Signature Scheme v2 + v3)"
javac -nowarn -cp "$BT" -d "$OUT" "$APP/tools/Sign.java"
java -cp "$BT:$OUT" Sign "$KEYSTORE" "$STORE_PASS" "$OUT/aligned.apk" "$OUT/Teka-$VERSION_NAME.apk"

step "Готово"
ls -la "$OUT/Teka-$VERSION_NAME.apk"
