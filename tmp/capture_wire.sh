#!/bin/bash
# Capture TCP packets from curl and Python POST to Brother scanner
pktmon stop 2>/dev/null
pktmon remove 2>/dev/null
pktmon filter add -i 10.1.121.175 -t TCP -p 80 2>/dev/null
pktmon start --capture --pkt-size 0 -m real-time --log-mode circular --file-size 50 -f "C:/Projects/mopria-android-scan-print/tmp/wire.etl" 2>/dev/null &
PKTMON_PID=$!
sleep 1

echo "=== curl ==="
curl.exe -s -m 10 -H "Content-Type: text/xml; charset=utf-8" \
  -d @"C:/Projects/mopria-android-scan-print/tmp/adf_test_scan_settings.xml" \
  "http://10.1.121.175/eSCL/ScanJobs" -o /dev/null -D - 2>&1 | head -3
sleep 1

echo "=== Python ==="
python -c "
import http.client
c = http.client.HTTPConnection('10.1.121.175', 80, timeout=10)
xml = open(r'C:\Projects\mopria-android-scan-print\tmp\adf_test_scan_settings.xml','rb').read()
c.request('POST', '/eSCL/ScanJobs', body=xml, headers={'Content-Type':'text/xml; charset=utf-8'})
r = c.getresponse(); print(f'Python: {r.status}'); r.read(); c.close()
" 2>&1
sleep 1

kill $PKTMON_PID 2>/dev/null
pktmon stop 2>/dev/null
sleep 1

pktmon etl2txt "C:/Projects/mopria-android-scan-print/tmp/wire.etl" -o "C:/Projects/mopria-android-scan-print/tmp/wire.txt" 2>/dev/null
echo "=== TCP handshakes ==="
grep -i "0x.. SYN\|0x.. SYN," "C:/Projects/mopria-android-scan-print/tmp/wire.txt" 2>/dev/null | head -10
echo "=== All packets to port 80 ==="
grep "10.1.121.175" "C:/Projects/mopria-android-scan-print/tmp/wire.txt" 2>/dev/null | head -20
