#!/usr/bin/env python3
"""
Test Feeder vs Platen from HOST using raw socket with TCP_NODELAY.
Uses the monkey-patched socket approach that we proved works for full-res.
"""
import socket
import struct
import time
import sys

SCANNER = "10.1.121.175"
PORT = 80

_orig_socket = socket.socket
class NoDelaySocket(_orig_socket):
    def connect(self, addr):
        super().connect(addr)
        self.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
socket.socket = NoDelaySocket

import http.client

def do_scan(label, source):
    print(f"\n=== {label} (source={source}) ===")

    xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
  <pwg:Version>2.63</pwg:Version>
  <scan:Intent>Document</scan:Intent>
  <pwg:ScanRegions><pwg:ScanRegion>
    <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
    <pwg:Height>3508</pwg:Height><pwg:Width>2480</pwg:Width>
    <pwg:XOffset>0</pwg:XOffset><pwg:YOffset>0</pwg:YOffset>
  </pwg:ScanRegion></pwg:ScanRegions>
  <scan:DocumentFormatExt>image/jpeg</scan:DocumentFormatExt>
  <pwg:InputSource>{source}</pwg:InputSource>
  <scan:XResolution>300</scan:XResolution><scan:YResolution>300</scan:YResolution>
  <scan:ColorMode>RGB24</scan:ColorMode>
</scan:ScanSettings>""".strip().encode()

    conn = http.client.HTTPConnection(SCANNER, PORT, timeout=30)
    conn.request("POST", "/eSCL/ScanJobs", body=xml, headers={
        "Content-Type": "text/xml; charset=utf-8",
        "Connection": "close",
    })
    resp = conn.getresponse()
    location = resp.getheader("Location")
    resp.read()
    conn.close()
    print(f"  POST: {resp.status}, Location: {location}")
    if not location:
        print("  FAILED!")
        return

    time.sleep(5)

    conn = http.client.HTTPConnection(SCANNER, PORT, timeout=120)
    conn.request("GET", location.replace(f"http://{SCANNER}", "") + "/NextDocument", headers={
        "Connection": "close",
    })
    resp = conn.getresponse()
    data = resp.read()
    conn.close()

    # Check scanner status to see what source was actually used
    conn2 = http.client.HTTPConnection(SCANNER, PORT, timeout=10)
    conn2.request("GET", "/eSCL/ScannerStatus")
    status_resp = conn2.getresponse()
    status_body = status_resp.read().decode()
    conn2.close()

    adf_state = "?"
    for part in status_body.split("<"):
        if "ScannerAdf" in part:
            adf_state = part.split(">")[0] if ">" in part else part
            break

    i = 0
    dims = "?"
    while i < len(data) - 9:
        if data[i] == 0xFF and data[i+1] == 0xC0:
            h = struct.unpack(">H", data[i+5:i+7])[0]
            w = struct.unpack(">H", data[i+7:i+9])[0]
            dims = f"{w}x{h}"
            break
        i += 1
    print(f"  Image: {dims} ({len(data)} bytes), ADF after: {adf_state}")

    # Save image
    fname = f"scan_{source.lower()}.jpg"
    with open(f"C:/Projects/mopria-android-scan-print/tmp/{fname}", "wb") as f:
        f.write(data)
    print(f"  Saved: {fname}")

    # Delete job
    conn = http.client.HTTPConnection(SCANNER, PORT, timeout=5)
    conn.request("DELETE", location.replace(f"http://{SCANNER}", ""))
    conn.getresponse().read()
    conn.close()

    return dims

# Test Feeder
feeder_dims = do_scan("FEEDER scan", "Feeder")

print(f"\n{'='*60}")
print(f"Feeder result: {feeder_dims}")
print(f"If image is similar to Platen, Brother is ignoring InputSource=Feeder")
