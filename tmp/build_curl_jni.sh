#!/bin/bash
# Manually build curl_jni.so using NDK clang - no CMake needed
set -e

NDK="/c/Users/av00021/AppData/Local/Android/Sdk/ndk/27.0.12077973"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
SRC="C:/Projects/mopria-android-scan-print/app/src/main/cpp/curl_jni.c"
TMP="C:/Projects/mopria-android-scan-print/tmp"
JNILIBS="C:/Projects/mopria-android-scan-print/app/src/main/jniLibs"
AAR="C:/Projects/mopria-android-scan-print/tmp/curl-7.82.0.aar"

# Extract libcurl.so for each ABI from the AAR into jniLibs
echo "=== Extracting libcurl.so from AAR ==="
for ABI in arm64-v8a armeabi-v7a x86 x86_64; do
    mkdir -p "$JNILIBS/$ABI"
    python -c "
import zipfile, os, shutil
with zipfile.ZipFile(r'$AAR') as z:
    for name in z.namelist():
        if f'android.{ABI}' in name and name.endswith('libcurl.so'):
            target = os.path.join(r'$JNILIBS', '$ABI', 'libcurl.so')
            with z.open(name) as src, open(target, 'wb') as dst:
                shutil.copyfileobj(src, dst)
            print(f'  Extracted: {target} ({os.path.getsize(target):,} bytes)')
            break
"
done

# Also extract headers
echo "=== Extracting curl headers ==="
mkdir -p "$TMP/curl_headers"
python -c "
import zipfile, os
with zipfile.ZipFile(r'$AAR') as z:
    for name in z.namelist():
        if 'android.x86_64/include/curl/' in name and name.endswith('.h'):
            basename = os.path.basename(name)
            target = os.path.join(r'$TMP/curl_headers', basename)
            with z.open(name) as src, open(target, 'wb') as dst:
                dst.write(src.read())
            print(f'  Header: {basename}')
"

# Compile curl_jni for each ABI
declare -A CLANG_TARGETS
CLANG_TARGETS[arm64-v8a]="aarch64-linux-android28"
CLANG_TARGETS[armeabi-v7a]="armv7a-linux-androideabi28"
CLANG_TARGETS[x86]="i686-linux-android28"
CLANG_TARGETS[x86_64]="x86_64-linux-android28"

echo "=== Building curl_jni.so ==="
for ABI in arm64-v8a x86_64; do
    echo "  Building $ABI..."
    CLANG="$TOOLCHAIN/bin/${CLANG_TARGETS[$ABI]}.cmd"
    "$CLANG" -shared -fPIC \
        -I"$NDK/sysroot/usr/include" \
        -I"$TMP/curl_headers" \
        -o "$JNILIBS/$ABI/libcurl_jni.so" \
        "$SRC" \
        -lcurl -llog \
        -L"$JNILIBS/$ABI" \
        -Wl,-rpath,\$ORIGIN
    echo "  → $JNILIBS/$ABI/libcurl_jni.so ($(stat -c%s "$JNILIBS/$ABI/libcurl_jni.so" 2>/dev/null || wc -c < "$JNILIBS/$ABI/libcurl_jni.so") bytes)"
done

echo "=== Done ==="
ls -la "$JNILIBS"/*/
