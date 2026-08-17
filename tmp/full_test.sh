#!/bin/bash
export PATH="$PATH:/c/Users/av00021/AppData/Local/Android/Sdk/platform-tools"

echo "=== Install ==="
adb install -r "C:/Projects/mopria-android-scan-print/app/build/outputs/apk/debug/app-debug.apk"
adb shell am start -n com.brianshih.mopria.android.scanprint/.MainActivity
sleep 3

echo "Pre-scan ADF: $(curl.exe -s -m 3 'http://10.1.121.175/eSCL/ScannerStatus' 2>/dev/null | grep -o 'ScannerAdf[A-Za-z]*')"

# Navigate to scan page
adb shell input tap 127 2211  # Home
sleep 1
adb shell input tap 366 678   # Scan document
sleep 2

# Scroll to Start scan
adb shell input swipe 540 1900 540 500 200
sleep 0.5
adb shell input swipe 540 1900 540 500 200
sleep 1

echo "Tapping Start scan..."
adb shell input tap 540 1916

for i in $(seq 1 15); do
  sleep 2
  ST=$(curl.exe -s -m 3 "http://10.1.121.175/eSCL/ScannerStatus" 2>/dev/null | grep -o '<pwg:State>[^<]*</pwg:State>')
  ADF=$(curl.exe -s -m 3 "http://10.1.121.175/eSCL/ScannerStatus" 2>/dev/null | grep -o 'ScannerAdf[A-Za-z]*')
  echo "  t+${i}x2s: $ST $ADF"
  if echo "$ST" | grep -q "Idle"; then
    if echo "$ADF" | grep -q "ScannerAdfEmpty"; then
      echo "  -> Scan complete!"
      sleep 4
      break
    fi
  fi
done

echo "=== Check result ==="
adb shell "run-as com.brianshih.mopria.android.scanprint ls -la files/scans/" 2>&1
adb shell "run-as com.brianshih.mopria.android.scanprint cat files/document_store.json" 2>&1
