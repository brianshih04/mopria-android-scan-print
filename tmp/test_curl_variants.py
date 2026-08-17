#!/usr/bin/env python3
"""
Systematic test: isolate what curl does differently from Python.
Test all combinations of HTTP version, headers, and connection behavior.
"""
import subprocess
import struct
import time

SCANNER = "10.1.121.175"
XML_FILE = r"C:\Projects\mopria-android-scan-print\tmp\adf_test_scan_settings.xml"

def curl_scan(label, extra_args=None):
    """Do a full scan using curl with given options."""
    extra_args = extra_args or []
    print(f"\n=== {label} ===")
    
    # POST
    args = ['curl.exe', '-s', '-m', '30', '-D', '-'] + extra_args + [
        '-H', 'Content-Type: text/xml; charset=utf-8',
        '-d', f'@{XML_FILE}',
        '-o', 'NUL',
        f'http://{SCANNER}/eSCL/ScanJobs'
    ]
    result = subprocess.run(args, capture_output=True, text=True, timeout=30)
    
    location = None
    for line in result.stdout.split('\n'):
        if line.lower().startswith('location:'):
            location = line.split(':', 1)[1].strip()
            break
    
    if not location:
        print(f"  POST FAILED: {result.stdout[:100]}")
        return None
    
    time.sleep(3)
    
    # GET NextDocument
    out = rf"C:\Projects\mopria-android-scan-print\tmp\curl_{label.replace(' ','_').lower()}.jpg"
    args = ['curl.exe', '-s', '-m', '120'] + extra_args + ['-o', out, f'{location}/NextDocument']
    subprocess.run(args, capture_output=True, timeout=120)
    
    with open(out, 'rb') as f:
        data = f.read()
    
    dims = "?"
    i = 0
    while i < len(data) - 9:
        if data[i] == 0xFF and data[i+1] == 0xC0:
            h = struct.unpack('>H', data[i+5:i+7])[0]
            w = struct.unpack('>H', data[i+7:i+9])[0]
            dims = f"{w}x{h}"
            break
        i += 1
    
    print(f"  {dims} ({len(data):,} bytes)")
    
    # Delete
    subprocess.run(['curl.exe', '-s', '-m', '5', '-X', 'DELETE', location],
                   capture_output=True, timeout=10)
    
    return dims

# Test 1: Standard curl (HTTP/1.1, default everything)
d1 = curl_scan("curl default")

# Test 2: HTTP/1.0
d2 = curl_scan("curl http1.0", ['--http1.0'])

# Test 3: HTTP/2 (if supported)
d3 = curl_scan("curl http2", ['--http2'])

# Test 4: With TCP_NODELAY disabled
d4 = curl_scan("curl no-tcp-nodelay", ['--no-tcp-nodelay'] if subprocess.run(['curl.exe', '--help', 'all'], capture_output=True, text=True).stdout.find('no-tcp-nodelay') >= 0 else [])

print(f"\n{'='*60}")
print(f"Summary:")
print(f"  curl default:    {d1}")
print(f"  curl HTTP/1.0:   {d2}")
print(f"  curl HTTP/2:     {d3}")
