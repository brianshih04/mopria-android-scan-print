#!/usr/bin/env python3
"""
Capture what Python http.client actually sends, then replicate with curl
to find the exact difference that causes the Brother to return low-res.
"""
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

# Test: POST with Connection: close (like curl does by default)
print("=== Test: Connection: close on POST ===")
conn = http.client.HTTPConnection(SCANNER, PORT, timeout=30)
conn.request("POST", "/eSCL/ScanJobs", body=xml, headers={
    "Content-Type": "text/xml; charset=utf-8",
    "Accept": "text/xml, application/xml",
    "Connection": "close",
})
resp = conn.getresponse()
location = resp.getheader("Location")
print(f"  POST: {resp.status}, Location: {location}")
resp.read()
conn.close()

time.sleep(3)

# GET with Connection: close
conn = http.client.HTTPConnection(SCANNER, PORT, timeout=120)
conn.request("GET", location.replace(f"http://{SCANNER}", "") + "/NextDocument", headers={
    "Accept": "image/jpeg, application/pdf",
    "Connection": "close",
})
resp = conn.getresponse()
data = resp.read()
conn.close()

i = 0
while i < len(data) - 9:
    if data[i] == 0xFF and data[i+1] == 0xC0:
        h = struct.unpack(">H", data[i+5:i+7])[0]
        w = struct.unpack(">H", data[i+7:i+9])[0]
        print(f"  JPEG: {w}x{h} ({len(data)} bytes)")
        break
    i += 1

# Delete
conn = http.client.HTTPConnection(SCANNER, PORT, timeout=5)
conn.request("DELETE", location.replace(f"http://{SCANNER}", ""))
conn.getresponse().read()
conn.close()

# Now test: what does Python actually send? Use http.client's debug
print("\n=== Python http.client debug output ===")
conn = http.client.HTTPConnection(SCANNER, PORT, timeout=10)
conn.set_debuglevel(1)
conn.request("GET", "/eSCL/ScannerStatus")
conn.getresponse().read()
conn.close()
