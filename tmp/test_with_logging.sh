#!/bin/bash
export PATH="$PATH:/c/Users/av00021/AppData/Local/Android/Sdk/platform-tools"

echo "ADF: $(curl.exe -s -m 3 'http://10.1.121.175/eSCL/ScannerStatus' 2>/dev/null | grep -o 'ScannerAdf[A-Za-z]*')"

adb install -r "C:/Projects/mopria-android-scan-print/app/build/outputs/apk/debug/app-debug.apk"
adb shell am start -n com.brianshih.mopria.android.scanprint/.MainActivity
sleep 3

adb logcat -c

# Navigate to scan
adb shell input tap 127 2211  # Home
sleep 1
adb shell input tap 366 678   # Scan document
sleep 2
adb shell input swipe 540 1900 540 500 200
sleep 0.5
adb shell input swipe 540 1900 540 500 200
sleep 1

echo "=== START SCAN ==="
adb shell input tap 540 1916

sleep 30

echo "=== LOGCAT (awaitJobReady) ==="
adb logcat -d -v time 2>&1 | grep "MopriaAwaitJobReady"

echo "=== Scan file ==="
LATEST=$(adb shell "run-as com.brianshih.mopria.android.scanprint ls -t files/scans/" 2>&1 | head -1 | tr -d '\r\n')
echo "Latest: $LATEST"
adb shell "run-as com.brianshih.mopria.android.scanprint cat files/scans/$LATEST" > "C:/Projects/mopria-android-scan-print/tmp/app_logged_scan.jpg" 2>/dev/null
python -c "
import struct
with open(r'C:\Projects\mopria-android-scan-print\tmp\app_logged_scan.jpg', 'rb') as f:
    data = f.read()
i = 0
while i < len(data) - 1:
    if data[i] == 0xFF and data[i+1] == 0xC0:
        h = struct.unpack('>H', data[i+5:i+7])[0]
        w = struct.unpack('>H', data[i+7:i+9])[0]
        print(f'Scan result: {w}x{h} ({len(data):,} bytes)')
        break
    i += 1
"
