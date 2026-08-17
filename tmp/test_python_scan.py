#!/usr/bin/env python3
"""Test direct HTTP scan from host using Python http.client (same as proxy uses)."""
import http.client
import struct
import time
import sys

SCANNER = "10.1.121.175"
PORT = 80

# 1. Check status
conn = http.client.HTTPConnection(SCANNER, PORT, timeout=10)
conn.request("GET", "/eSCL/ScannerStatus")
resp = conn.getresponse()
body = resp.read().decode()
adf_state = "unknown"
for line in body.split(">"):
    if "ScannerAdf" in line:
        adf_state = line.strip()
        break
print(f"Status: {resp.status}, ADF: {adf_state}")
conn.close()

if "Loaded" not in adf_state:
    print("ADF not loaded! Put paper in ADF first.")
    sys.exit(1)

# 2. POST scan job
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

conn = http.client.HTTPConnection(SCANNER, PORT, timeout=30)
conn.request("POST", "/eSCL/ScanJobs", body=xml, headers={
    "Content-Type": "text/xml; charset=utf-8",
    "Accept": "text/xml, application/xml",
})
resp = conn.getresponse()
location = resp.getheader("Location")
print(f"POST: {resp.status}, Location: {location}")
resp.read()
conn.close()

if not location:
    print("No Location!")
    sys.exit(1)

time.sleep(3)

# 3. GET NextDocument
conn = http.client.HTTPConnection(SCANNER, PORT, timeout=120)
conn.request("GET", f"{location.replace(f'http://{SCANNER}', '')}/NextDocument", headers={
    "Accept": "image/jpeg, application/pdf, image/png, application/octet-stream",
})
resp = conn.getresponse()
print(f"NextDocument: {resp.status}, Content-Type: {resp.getheader('Content-Type')}")
data = resp.read()
print(f"Body size: {len(data)} bytes")

# Parse JPEG dimensions
i = 0
while i < len(data) - 9:
    if data[i] == 0xFF and data[i+1] == 0xC0:
        h = struct.unpack(">H", data[i+5:i+7])[0]
        w = struct.unpack(">H", data[i+7:i+9])[0]
        print(f"JPEG dimensions: {w}x{h}")
        break
    i += 1
conn.close()

# 4. Delete job
conn = http.client.HTTPConnection(SCANNER, PORT, timeout=5)
conn.request("DELETE", location.replace(f"http://{SCANNER}", ""))
resp = conn.getresponse()
print(f"DELETE: {resp.status}")
conn.close()

print()
print(f"curl reference: 2448x3470 (524KB)")
