#!/bin/bash
export PATH="$PATH:/c/Users/av00021/AppData/Local/Android/Sdk/platform-tools"

echo "ADF: $(curl.exe -s -m 3 'http://10.1.121.175/eSCL/ScannerStatus' 2>/dev/null | grep -o 'ScannerAdf[A-Za-z]*')"

echo "=== TEST: curl with app-like headers ==="
LOCATION=$(curl.exe -s -m 30 -D - \
  -H "Content-Type: text/xml; charset=utf-8" \
  -H "User-Agent: MopriaScanPrint/0.1" \
  -H "Accept: text/xml, application/xml" \
  -H "Accept-Encoding: gzip" \
  -H "Connection: Keep-Alive" \
  -d @"C:/Projects/mopria-android-scan-print/tmp/adf_test_scan_settings.xml" \
  "http://10.1.121.175/eSCL/ScanJobs" 2>&1 | grep -i "^Location:" | tr -d '\r\n')
JOB_URL=$(echo "$LOCATION" | awk '{print $2}')
echo "Job: $JOB_URL"

sleep 5

curl.exe -s -m 60 \
  -H "Accept: image/jpeg, application/pdf, image/png, application/octet-stream" \
  -H "Connection: close" \
  -H "Accept-Encoding: gzip" \
  -H "User-Agent: MopriaScanPrint/0.1" \
  -o "C:/Projects/mopria-android-scan-print/tmp/curl_applike_scan.jpg" \
  "$JOB_URL/NextDocument"

python -c "
import struct
with open(r'C:\Projects\mopria-android-scan-print\tmp\curl_applike_scan.jpg', 'rb') as f:
    data = f.read()
i = 0
while i < len(data) - 1:
    if data[i] == 0xFF and data[i+1] == 0xC0:
        h = struct.unpack('>H', data[i+5:i+7])[0]
        w = struct.unpack('>H', data[i+7:i+9])[0]
        print(f'App-header scan: {w}x{h} ({len(data):,} bytes)')
        break
    i += 1
print(f'Curl plain scan: 2448x3470 (524,237 bytes)')
print(f'App scan result: 1680x2193')
"
