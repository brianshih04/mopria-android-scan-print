#!/bin/bash
export PATH="$PATH:/c/Users/av00021/AppData/Local/Android/Sdk/platform-tools"

echo "ADF: $(curl.exe -s -m 3 'http://10.1.121.175/eSCL/ScannerStatus' 2>/dev/null | grep -o 'ScannerAdf[A-Za-z]*')"

echo ""
echo "=== TEST A: ZERO delay between POST and NextDocument ==="
LOCATION=$(curl.exe -s -m 30 -D - \
  -H "Content-Type: text/xml; charset=utf-8" \
  -d @"C:/Projects/mopria-android-scan-print/tmp/adf_test_scan_settings.xml" \
  "http://10.1.121.175/eSCL/ScanJobs" 2>&1 | grep -i "^Location:" | tr -d '\r\n')
JOB_URL=$(echo "$LOCATION" | awk '{print $2}')
echo "Job: $JOB_URL"

# NO DELAY - immediately fetch NextDocument
curl.exe -s -m 120 -o "C:/Projects/mopria-android-scan-print/tmp/curl_nodelay.jpg" "$JOB_URL/NextDocument"
python -c "
import struct
with open(r'C:\Projects\mopria-android-scan-print\tmp\curl_nodelay.jpg', 'rb') as f:
    data = f.read()
i = 0
while i < len(data) - 1:
    if data[i] == 0xFF and data[i+1] == 0xC0:
        h = struct.unpack('>H', data[i+5:i+7])[0]
        w = struct.unpack('>H', data[i+7:i+9])[0]
        print(f'  ZERO delay: {w}x{h} ({len(data):,} bytes)')
        break
    i += 1
else:
    print(f'  Could not parse JPEG, size={len(data)}')
"

echo ""
echo "=== TEST B: 5s delay (our standard curl test) ==="
LOCATION2=$(curl.exe -s -m 30 -D - \
  -H "Content-Type: text/xml; charset=utf-8" \
  -d @"C:/Projects/mopria-android-scan-print/tmp/adf_test_scan_settings.xml" \
  "http://10.1.121.175/eSCL/ScanJobs" 2>&1 | grep -i "^Location:" | tr -d '\r\n')
JOB_URL2=$(echo "$LOCATION2" | awk '{print $2}')
echo "Job: $JOB_URL2"

sleep 5
curl.exe -s -m 120 -o "C:/Projects/mopria-android-scan-print/tmp/curl_5delay.jpg" "$JOB_URL2/NextDocument"
python -c "
import struct
with open(r'C:\Projects\mopria-android-scan-print\tmp\curl_5delay.jpg', 'rb') as f:
    data = f.read()
i = 0
while i < len(data) - 1:
    if data[i] == 0xFF and data[i+1] == 0xC0:
        h = struct.unpack('>H', data[i+5:i+7])[0]
        w = struct.unpack('>H', data[i+7:i+9])[0]
        print(f'  5s delay: {w}x{h} ({len(data):,} bytes)')
        break
    i += 1
"
