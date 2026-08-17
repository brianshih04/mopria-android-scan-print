#!/usr/bin/env python3
"""Test if Accept-Encoding header is the cause."""
import http.client
import struct
import time

SCANNER = "10.1.121.175"
PORT = 80

xml = b"""<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
  <pwg:Version>2.63</pwg:Version>
  <scan:Intent>Document</scan:Intent>
  <pwg:ScanRegions>
    <pwg:ScanRegion>
      <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
      <pwg:Height>3508</pwg:Height>
      <pwg:Width>2480</pwg:Width>
      <pwg:XOffset>0</pwg:XOffset>
      <pwg:YOffset>0</pwg:YOffset>
    </pwg:ScanRegion>
  </pwg:ScanRegions>
  <scan:DocumentFormatExt>image/jpeg</scan:DocumentFormatExt>
  <pwg:InputSource>Feeder</pwg:InputSource>
  <scan:XResolution>300</scan:XResolution>
  <scan:YResolution>300</scan:YResolution>
  <scan:ColorMode>RGB24</scan:ColorMode>
</scan:ScanSettings>"""

def do_scan(label, extra_post_headers=None, extra_get_headers=None):
    print(f"\n=== {label} ===")
    
    # POST
    headers = {"Content-Type": "text/xml; charset=utf-8", "Accept": "text/xml, application/xml"}
    if extra_post_headers:
        headers.update(extra_post_headers)
    
    conn = http.client.HTTPConnection(SCANNER, PORT, timeout=30)
    conn.request("POST", "/eSCL/ScanJobs", body=xml, headers=headers)
    resp = conn.getresponse()
    location = resp.getheader("Location")
    resp.read()
    conn.close()
    print(f"  POST: {resp.status}, Location: {location}")
    if not location:
        return
    
    time.sleep(3)
    
    # GET NextDocument
    headers = {"Accept": "image/jpeg, application/pdf"}
    if extra_get_headers:
        headers.update(extra_get_headers)
    
    conn = http.client.HTTPConnection(SCANNER, PORT, timeout=120)
    path = location.replace(f"http://{SCANNER}", "") + "/NextDocument"
    conn.request("GET", path, headers=headers)
    resp = conn.getresponse()
    data = resp.read()
    conn.close()
    
    # Parse JPEG
    i = 0
    dims = "?"
    while i < len(data) - 9:
        if data[i] == 0xFF and data[i+1] == 0xC0:
            h = struct.unpack(">H", data[i+5:i+7])[0]
            w = struct.unpack(">H", data[i+7:i+9])[0]
            dims = f"{w}x{h}"
            break
        i += 1
    
    print(f"  NextDocument: {resp.status}, {len(data)} bytes, {dims}")
    
    # Delete
    conn = http.client.HTTPConnection(SCANNER, PORT, timeout=5)
    conn.request("DELETE", location.replace(f"http://{SCANNER}", ""))
    conn.getresponse().read()
    conn.close()

# Test 1: Default Python (no Accept-Encoding override)
do_scan("Python default")

# Test 2: With Accept-Encoding: gzip (like Android HttpURLConnection)
do_scan("Accept-Encoding: gzip", 
        extra_post_headers={"Accept-Encoding": "gzip"},
        extra_get_headers={"Accept-Encoding": "gzip"})

# Test 3: With Accept-Encoding: identity (what Python sends by default for GET)
do_scan("Accept-Encoding: identity",
        extra_post_headers={"Accept-Encoding": "identity"},
        extra_get_headers={"Accept-Encoding": "identity"})

# Test 4: NO Accept-Encoding at all (force skip)
do_scan("No Accept-Encoding (curl-like)",
        extra_post_headers={"Accept-Encoding": None},
        extra_get_headers={"Accept-Encoding": None})
