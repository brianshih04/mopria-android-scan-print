#!/usr/bin/env python3
"""Extract libcurl.so from AAR and compile curl_jni.so using NDK clang."""
import zipfile
import os
import subprocess
import shutil

AAR = r"C:\Projects\mopria-android-scan-print\tmp\curl-7.82.0.aar"
SRC = r"C:\Projects\mopria-android-scan-print\app\src\main\cpp\curl_jni.c"
JNILIBS = r"C:\Projects\mopria-android-scan-print\app\src\main\jniLibs"
TMP = r"C:\Projects\mopria-android-scan-print\tmp"
NDK = r"C:\Users\av00021\AppData\Local\Android\Sdk\ndk\27.0.12077973"
TOOLCHAIN = os.path.join(NDK, "toolchains", "llvm", "prebuilt", "windows-x86_64")

CLANG_TARGETS = {
    "arm64-v8a": "aarch64-linux-android28",
    "x86_64": "x86_64-linux-android28",
}

# 1. Extract libcurl.so and headers from AAR
print("=== Extracting libcurl.so + headers ===")
headers_dir = os.path.join(TMP, "curl_headers")
os.makedirs(headers_dir, exist_ok=True)

with zipfile.ZipFile(AAR) as z:
    for name in z.namelist():
        # Extract libcurl.so for each ABI
        for abi in CLANG_TARGETS:
            abi_key = f"android.{abi}"
            if abi_key in name and name.endswith("libcurl.so"):
                target = os.path.join(JNILIBS, abi, "libcurl.so")
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with z.open(name) as src, open(target, "wb") as dst:
                    shutil.copyfileobj(src, dst)
                print(f"  libcurl.so → {abi} ({os.path.getsize(target):,} bytes)")
        # Extract headers (from x86_64 build, same for all ABIs)
        if "android.x86_64/include/curl/" in name and name.endswith(".h"):
            basename = os.path.basename(name)
            target = os.path.join(headers_dir, basename)
            with z.open(name) as src, open(target, "wb") as dst:
                dst.write(src.read())

print(f"  Headers: {len(os.listdir(headers_dir))} files")

# 2. Compile curl_jni.so for each ABI
print("\n=== Building curl_jni.so ===")
for abi, target in CLANG_TARGETS.items():
    print(f"  Building {abi}...")
    clang = os.path.join(TOOLCHAIN, "bin", f"{target}.cmd")
    output = os.path.join(JNILIBS, abi, "libcurl_jni.so")
    
    cmd = [
        clang, "-shared", "-fPIC",
        f"-I{os.path.join(NDK, 'sysroot', 'usr', 'include')}",
        f"-I{headers_dir}",
        "-o", output,
        SRC,
        "-lcurl", "-llog",
        f"-L{os.path.join(JNILIBS, abi)}",
        "-Wl,-rpath,$ORIGIN",
    ]
    
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    if result.returncode != 0:
        print(f"  ERROR: {result.stderr[:300]}")
    else:
        size = os.path.getsize(output)
        print(f"  → {output} ({size:,} bytes)")

# 3. Show results
print("\n=== Results ===")
for abi in CLANG_TARGETS:
    d = os.path.join(JNILIBS, abi)
    if os.path.exists(d):
        for f in os.listdir(d):
            p = os.path.join(d, f)
            print(f"  {abi}/{f} ({os.path.getsize(p):,} bytes)")
