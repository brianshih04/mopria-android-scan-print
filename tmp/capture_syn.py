#!/usr/bin/env python3
"""
Capture TCP SYN packets from curl vs Python to compare TCP options.
Uses pktmon on Windows.
"""
import subprocess
import time

# Start pktmon capture (filter for SYN to scanner)
subprocess.run(['pktmon', 'stop'], capture_output=True)
subprocess.run(['pktmon', 'remove'], capture_output=True)

# Add filter for TCP SYN to 10.1.121.175
subprocess.run(['pktmon', 'filter', 'add', '-i', '10.1.121.175', '-t', 'TCP', '-p', '80'], capture_output=True)

# Start capture
subprocess.run(['pktmon', 'start', '-c', '--capture', '--pkt-size', '128', '-f', 'C:/Projects/mopria-android-scan-print/tmp/syn_capture.etl'], capture_output=True)

print("=== Capture started ===")
time.sleep(1)

# Run curl
print("Running curl...")
subprocess.run(['curl.exe', '-s', '-m', '3', '-o', 'NUL', 'http://10.1.121.175/eSCL/ScannerStatus'],
               capture_output=True, timeout=10)
time.sleep(1)

# Run Python http.client
print("Running Python http.client...")
import http.client
conn = http.client.HTTPConnection("10.1.121.175", 80, timeout=5)
conn.request("GET", "/eSCL/ScannerStatus")
conn.getresponse().read()
conn.close()
time.sleep(1)

# Stop capture
subprocess.run(['pktmon', 'stop'], capture_output=True)
print("=== Capture stopped ===")

# Convert to text
result = subprocess.run(['pktmon', 'etl2txt', 'C:/Projects/mopria-android-scan-print/tmp/syn_capture.etl',
                         '-o', 'C:/Projects/mopria-android-scan-print/tmp/syn_capture.txt'],
                        capture_output=True, text=True, timeout=30)
print(result.stdout[:200])
print(result.stderr[:200])

# Read and show SYN packets
import os
txt_file = 'C:/Projects/mopria-android-scan-print/tmp/syn_capture.txt'
if os.path.exists(txt_file):
    with open(txt_file) as f:
        for line in f:
            if 'SYN' in line or '0x10' in line[:20]:
                print(line.rstrip()[:120])
