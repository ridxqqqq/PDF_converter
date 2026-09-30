#!/usr/bin/env bash
# Manual APK build (no Gradle / no AndroidX / no Material).
# Toolchain from dl.google.com; only external dep is pdfbox-android (Maven Central).
set -e

ROOT="/c/Users/Administrator/WorkBuddy/2026-09-11-18-31-41"
PROJ="$ROOT/PdfToolApp"
SDK="$ROOT/android-sdk"
BT="$SDK/build-tools/android-14"
PLAT="$SDK/platforms/android-11/android.jar"
AAPT2="$BT/aapt2.exe"
ZIPALIGN="$BT/zipalign.exe"
JAVA="/c/Program Files/BellSoft/LibericaJDK-17/bin/java.exe"
JAVAC="/c/Program Files/BellSoft/LibericaJDK-17/bin/javac.exe"
JAR="/c/Program Files/BellSoft/LibericaJDK-17/bin/jar.exe"
KEYTOOL="/c/Program Files/BellSoft/LibericaJDK-17/bin/keytool.exe"
PYTHON="/c/Users/Administrator/.workbuddy/binaries/python/versions/3.13.12/python.exe"
KOTLINC_JAR="$ROOT/_dl/kotlin-compiler.jar"
TROVE="$ROOT/_dl/trove4j.jar"
ANNOTATIONS="$ROOT/_dl/annotations-13.0.jar"
KOTLIN_STDLIB="$ROOT/_dl/kotlin-stdlib.jar"
PDFBOX="$ROOT/_dl/pdfbox-aar/classes.jar"
AAR="$ROOT/_dl/pdfbox-android.aar"
BUILD="$(mktemp -d 2>/dev/null || echo "$ROOT/_dl/build")"

# Mixed Windows paths (C:/...) -> accepted by java/kotlinc/d8/python AND aapt2
PROJ=$(cygpath -m "$PROJ")
PLAT=$(cygpath -m "$PLAT")
AAPT2=$(cygpath -m "$AAPT2")
ZIPALIGN=$(cygpath -m "$ZIPALIGN")
BT=$(cygpath -m "$BT")
BUILD=$(cygpath -m "$BUILD")
KOTLINC_JAR=$(cygpath -m "$KOTLINC_JAR")
TROVE=$(cygpath -m "$TROVE")
ANNOTATIONS=$(cygpath -m "$ANNOTATIONS")
KOTLIN_STDLIB=$(cygpath -m "$KOTLIN_STDLIB")
PDFBOX=$(cygpath -m "$PDFBOX")
AAR=$(cygpath -m "$AAR")
# Compiler JVM classpath: kotlin-compiler + trove4j + annotations (NotNull for codegen) + stdlib
KOTLINC_CP="$KOTLINC_JAR;$TROVE;$ANNOTATIONS;$KOTLIN_STDLIB"

CP_JAVAC="$PLAT;$PDFBOX;$KOTLIN_STDLIB"
CP_KOTLIN="$PLAT;$PDFBOX;$KOTLIN_STDLIB;$BUILD/classes"

echo "==> prepare build dir: $BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "==> [1/6] aapt2 compile resources"
"$AAPT2" compile --dir "$PROJ/app/src/main/res" -o "$BUILD/res.zip"

echo "==> [2/6] aapt2 link (resources + R.java)"
"$AAPT2" link -o "$BUILD/app-unsigned.apk" -I "$PLAT" \
  --manifest "$PROJ/app/src/main/AndroidManifest.xml" \
  -R "$BUILD/res.zip" --java "$BUILD/gen" --custom-package com.pdftool.app \
  --auto-add-overlay

echo "==> [3/6] javac R.java"
"$JAVAC" -cp "$CP_JAVAC" -d "$BUILD/classes" "$BUILD/gen/com/pdftool/app/R.java"

echo "==> [4/6] kotlinc compile Kotlin"
KTS=""
while IFS= read -r f; do KTS="$KTS $(cygpath -m "$f")"; done < <(find "$ROOT/PdfToolApp/app/src/main/java" -name '*.kt')
"$JAVA" -Dfile.encoding=UTF-8 -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$CP_KOTLIN" -jvm-target 1.8 -d "$BUILD/classes" $KTS

echo "==> [5/6] package classes + d8 dex"
"$JAR" cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --min-api 23 --output "$BUILD/dex" "$BUILD/classes.jar" "$PDFBOX" "$KOTLIN_STDLIB"

echo "==> [6/6] assemble + zipalign + sign"
"$PYTHON" - <<PYEOF
import zipfile, shutil, glob, os
b = r"$BUILD"
aar = r"$AAR"
unsigned = os.path.join(b, "app-unsigned.apk")
withdex = os.path.join(b, "with-dex.apk")
shutil.copyfile(unsigned, withdex)

# (a) extract pdfbox-android font/CMap resources from the AAR into a temp dir
assets_src = os.path.join(b, "pdfbox_assets")
if os.path.exists(assets_src):
    shutil.rmtree(assets_src)
with zipfile.ZipFile(aar) as z:
    for n in z.namelist():
        if n.startswith("assets/") and not n.endswith("/"):
            dest = os.path.join(assets_src, n[len("assets/"):])
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with z.open(n) as src, open(dest, "wb") as out:
                shutil.copyfileobj(src, out)
print("extracted pdfbox assets:", len(glob.glob(os.path.join(assets_src, '**'), recursive=True)), "entries")

# (b) inject dex (must be STORED)
with zipfile.ZipFile(withdex, "a") as z:
    for dex in sorted(glob.glob(os.path.join(b, "dex", "classes*.dex"))):
        name = os.path.basename(dex)
        with open(dex, "rb") as f:
            data = f.read()
        zi = zipfile.ZipInfo(name)
        zi.compress_type = zipfile.ZIP_STORED
        z.writestr(zi, data)
        print("added", name, len(data), "bytes")

    # (c) bundle pdfbox assets under assets/ (DEFLATED is fine for AssetManager)
    for root, _, files in os.walk(assets_src):
        for fn in files:
            full = os.path.join(root, fn)
            rel = os.path.relpath(full, assets_src).replace(os.sep, "/")
            with open(full, "rb") as f:
                data = f.read()
            zi = zipfile.ZipInfo("assets/" + rel)
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, data)
    print("added pdfbox assets under assets/")

print("injected dex+assets into", withdex)
PYEOF

"$ZIPALIGN" -p 4 "$BUILD/with-dex.apk" "$BUILD/aligned.apk"

# Reuse the persisted release key (same signature as v1.0.0) so the new
# APK installs over the old one without uninstalling.
KEYSTORE="$ROOT/_dl/pdftool-key.jks"
if [ ! -f "$KEYSTORE" ]; then
  echo "==> generate debug keystore"
  "$KEYTOOL" -genkeypair -v -keystore "$KEYSTORE" -alias pdftool \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass pdftool -keypass pdftool \
    -dname "CN=PDFTool,OU=Dev,O=PDFTool,L=CN,S=CN,C=CN"
fi

echo "==> apksigner sign"
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KEYSTORE" --ks-key-alias pdftool \
  --ks-pass pass:pdftool --key-pass pass:pdftool \
  --out "$PROJ/app-release.apk" "$BUILD/aligned.apk"

echo "==> verify"
"$JAVA" -jar "$BT/lib/apksigner.jar" verify --verbose "$PROJ/app-release.apk" | head -25
echo "BUILD DONE -> $PROJ/app-release.apk"
ls -la "$PROJ/app-release.apk"
