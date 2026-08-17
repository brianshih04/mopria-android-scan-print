#!/bin/bash
export PATH="$PATH:/c/Users/av00021/AppData/Local/Android/Sdk/platform-tools"

echo "=== Install APK ==="
adb install -r "C:/Projects/mopria-android-scan-print/app/build/outputs/apk/debug/app-debug.apk"
adb shell am start -n com.brianshih.mopria.android.scanprint/.MainActivity
sleep 3
adb logcat -c

# Navigate: Home → Scan → ADF → Start scan
adb shell input tap 127 2211  # Home
sleep 1
adb shell input tap 366 678   # Scan document
sleep 2
# Scroll to Start scan
adb shell input swipe 540 1900 540 500 200
sleep 0.5
adb shell input swipe 540 1900 540 500 200
sleep 1

echo "=== START SCAN ==="
adb shell input tap 540 1916

# Monitor
for i in $(seq 1 20); do
  sleep 2
  ST=$(curl.exe -s -m 3 "http://10.1.121.175/eSCL/ScannerStatus" 2>/dev/null | grep -o '<pwg:State>[^<]*</pwg:State>')
  ADF=$(curl.exe -s -m 3 "http://10.1.121.175/eSCL/ScannerStatus" 2>/dev/null | grep -o 'ScannerAdf[A-Za-z]*')
  echo "  t+${i}x2s: $ST $ADF"
  if echo "$ADF" | grep -q "ScannerAdfEmpty"; then
    if echo "$ST" | grep -q "Idle"; then
      echo "  -> Scan done!"
      sleep 3
      break
    fi
  fi
done

echo "=== LOGCAT ==="
adb logcat -d -v time 2>&1 | grep -i "CurlJni\|MopriaScan\|CurlEngine\|curl" | tail -10

echo "=== SCAN RESULT ==="
LATEST=$(adb shell "run-as com.brianshih.mopria.android.scanprint ls -t files/scans/" 2>&1 | head -1 | tr -d '\r\n')
echo "Latest: $LATEST"
adb shell "run-as com.brianshih.mopria.android.scanprint cat files/scans/$LATEST" > "C:/Projects/mopria-android-scan-print/tmp/app_curl_scan.jpg" 2>/dev/null
python -c "
import struct
with open(r'C:\Projects\mopria-android-scan-print\tmp\app_curl_scan.jpg', 'rb') as f:
    data = f.read()
i = 0
while i < len(data) - 9:
    if data[i] == 0xFF and data[i+1] == 0xC0:
        h = struct.unpack('>H', data[i+5:i+7])[0]
        w = struct.unpack('>H', data[i+7:i+9])[0]
        print(f'JPEG: {w}x{h} ({len(data):,} bytes)')
        break
    i += 1
"
