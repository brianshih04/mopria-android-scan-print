#!/bin/bash
export PATH="$PATH:/c/Users/av00021/AppData/Local/Android/Sdk/platform-tools"

echo "Pre-scan: $(curl.exe -s -m 3 'http://10.1.121.175/eSCL/ScannerStatus' 2>/dev/null | grep -o 'ScannerAdf[A-Za-z]*')"

echo "Tapping Start scan..."
adb shell input tap 540 1916

for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
  sleep 2
  STATE=$(curl.exe -s -m 3 "http://10.1.121.175/eSCL/ScannerStatus" 2>/dev/null | grep -o 'ScannerAdf[A-Za-z]*')
  ST=$(curl.exe -s -m 3 "http://10.1.121.175/eSCL/ScannerStatus" 2>/dev/null | grep -o '<pwg:State>[^<]*</pwg:State>')
  echo "  t+${i}x2s: $ST $STATE"
  if echo "$STATE" | grep -q "ScannerAdfEmpty"; then
    if echo "$ST" | grep -q "Idle"; then
      echo "  -> Scan complete"
      break
    fi
  fi
done
