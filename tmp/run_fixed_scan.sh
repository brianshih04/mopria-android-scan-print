#!/bin/bash
export PATH="$PATH:/c/Users/av00021/AppData/Local/Android/Sdk/platform-tools"

echo "Pre-scan ADF: $(curl.exe -s -m 3 'http://10.1.121.175/eSCL/ScannerStatus' 2>/dev/null | grep -o 'ScannerAdf[A-Za-z]*')"

# Tap Scan document
adb shell input tap 366 678
sleep 2

# Scroll down to Start scan
adb shell input swipe 540 1900 540 500 200
sleep 0.5
adb shell input swipe 540 1900 540 500 200
sleep 1

# Tap Start scan
echo "Tapping Start scan..."
adb shell input tap 540 1916

# Monitor
for i in $(seq 1 15); do
  sleep 2
  ST=$(curl.exe -s -m 3 "http://10.1.121.175/eSCL/ScannerStatus" 2>/dev/null | grep -o '<pwg:State>[^<]*</pwg:State>')
  ADF=$(curl.exe -s -m 3 "http://10.1.121.175/eSCL/ScannerStatus" 2>/dev/null | grep -o 'ScannerAdf[A-Za-z]*')
  echo "  t+${i}x2s: $ST $ADF"
  if echo "$ST" | grep -q "Idle"; then
    if echo "$ADF" | grep -q "ScannerAdfEmpty"; then
      echo "  -> Scan complete!"
      sleep 3
      break
    fi
  fi
done
