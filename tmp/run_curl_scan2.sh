#!/bin/bash
echo "=== Curl ADF scan #2 ==="
LOCATION=$(curl.exe -s -m 30 -D - \
  -H "Content-Type: text/xml; charset=utf-8" \
  -d @"C:/Projects/mopria-android-scan-print/tmp/adf_test_scan_settings.xml" \
  "http://10.1.121.175/eSCL/ScanJobs" 2>&1 | grep -i "^Location:" | tr -d '\r\n')
JOB_URL=$(echo "$LOCATION" | awk '{print $2}')
echo "Location: $JOB_URL"

sleep 2
curl.exe -s -m 120 -o "C:/Projects/mopria-android-scan-print/tmp/adf_curl_scan2.jpg" "$JOB_URL/NextDocument" 2>&1

echo "=== File info ==="
file "C:/Projects/mopria-android-scan-print/tmp/adf_curl_scan2.jpg"
echo "=== Comparison ==="
echo "Curl #1: $(file C:/Projects/mopria-android-scan-print/tmp/adf_scan_page1.jpg | grep -o '[0-9]*x[0-9]*')"
echo "Curl #2: $(file C:/Projects/mopria-android-scan-print/tmp/adf_curl_scan2.jpg | grep -o '[0-9]*x[0-9]*')"
echo "App:     1680x2193"
