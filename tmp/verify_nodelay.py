#!/usr/bin/env python3
"""Verify TCP_NODELAY is set and test multiple times to check consistency."""
import socket
import struct
import time
import http.client

SCANNER = "10.1.121.175"

_orig_socket = socket.socket
class NoDelaySocket(_orig_socket):
    def connect(self, addr):
        super().connect(addr)
        self.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        nodelay = self.getsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY)
        print(f"  [socket] TCP_NODELAY={nodelay} connected to {addr}")
socket.socket = NoDelaySocket

xml = b"""<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
  <pwg:Version>2.63</pwg:Version><scan:Intent>Document</scan:Intent>
  <pwg:ScanRegions><pwg:ScanRegion><pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
  <pwg:Height>3508</pwg:Height><pwg:Width>2480</pwg:Width><pwg:XOffset>0</pwg:XOffset><pwg:YOffset>0</pwg:YOffset>
  </pwg:ScanRegion></pwg:ScanRegions><scan:DocumentFormatExt>image/jpeg</scan:DocumentFormatExt>
  <pwg:InputSource>Feeder</pwg:InputSource><scan:XResolution>300</scan:XResolution>
  <scan:YResolution>300</scan:YResolution><scan:ColorMode>RGB24</scan:ColorMode></scan:ScanSettings>"""

def one_scan():
    conn = http.client.HTTPConnection(SCANNER, 80, timeout=30)
    conn.request("POST", "/eSCL/ScanJobs", body=xml, headers={
        "Content-Type": "text/xml; charset=utf-8", "Connection": "close"})
    resp = conn.getresponse()
    loc = resp.getheader("Location")
    resp.read()
    conn.close()
    if not loc:
        print("  POST FAILED")
        return None
    time.sleep(3)
    conn = http.client.HTTPConnection(SCANNER, 80, timeout=120)
    conn.request("GET", loc.replace(f"http://{SCANNER}","") + "/NextDocument", headers={"Connection":"close"})
    resp = conn.getresponse()
    data = resp.read()
    conn.close()
    conn = http.client.HTTPConnection(SCANNER, 80, timeout=5)
    conn.request("DELETE", loc.replace(f"http://{SCANNER}",""))
    conn.getresponse().read()
    conn.close()
    i = 0
    while i < len(data) - 9:
        if data[i] == 0xFF and data[i+1] == 0xC0:
            h = struct.unpack(">H", data[i+5:i+7])[0]
            w = struct.unpack(">H", data[i+7:i+9])[0]
            return f"{w}x{h} ({len(data)} bytes)"
        i += 1
    return f"? ({len(data)} bytes)"

print(f"ADF: ", end="")
status_conn = http.client.HTTPConnection(SCANNER, 80, timeout=5)
status_conn.request("GET", "/eSCL/ScannerStatus")
sb = status_conn.getresponse().read().decode()
status_conn.close()
if "ScannerAdfLoaded" in sb:
    print("Loaded")
    result = one_scan()
    print(f"Result: {result}")
else:
    print("Empty - need paper!")
