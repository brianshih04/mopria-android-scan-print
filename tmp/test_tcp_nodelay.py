#!/usr/bin/env python3
"""Test: does TCP_NODELAY fix the resolution issue?"""
import socket
import struct
import time

SCANNER = "10.1.121.175"
PORT = 80

xml = b"""<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
  <pwg:Version>2.63</pwg:Version>
  <scan:Intent>Document</scan:Intent>
  <pwg:ScanRegions><pwg:ScanRegion>
    <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
    <pwg:Height>3508</pwg:Height><pwg:Width>2480</pwg:Width>
    <pwg:XOffset>0</pwg:XOffset><pwg:YOffset>0</pwg:YOffset>
  </pwg:ScanRegion></pwg:ScanRegions>
  <scan:DocumentFormatExt>image/jpeg</scan:DocumentFormatExt>
  <pwg:InputSource>Feeder</pwg:InputSource>
  <scan:XResolution>300</scan:XResolution><scan:YResolution>300</scan:YResolution>
  <scan:ColorMode>RGB24</scan:ColorMode>
</scan:ScanSettings>"""

def http_exchange(label, use_nodelay):
    print(f"\n=== {label} (TCP_NODELAY={use_nodelay}) ===")
    
    # POST
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(30)
    if use_nodelay:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.connect((SCANNER, PORT))
    
    req = f"POST /eSCL/ScanJobs HTTP/1.1\r\nHost: {SCANNER}\r\nContent-Type: text/xml; charset=utf-8\r\nContent-Length: {len(xml)}\r\nConnection: close\r\n\r\n".encode()
    sock.sendall(req + xml)
    
    resp = b""
    while True:
        try:
            chunk = sock.recv(65536)
            if not chunk: break
            resp += chunk
        except: break
    sock.close()
    
    # Find Location
    location = None
    for line in resp.split(b"\r\n"):
        if line.lower().startswith(b"location:"):
            location = line.split(b":", 1)[1].strip().decode()
            break
    print(f"  POST location: {location}")
    if not location: return
    
    time.sleep(3)
    
    # GET NextDocument
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(120)
    if use_nodelay:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.connect((SCANNER, PORT))
    
    path = location.replace(f"http://{SCANNER}", "") + "/NextDocument"
    req = f"GET {path} HTTP/1.1\r\nHost: {SCANNER}\r\nConnection: close\r\n\r\n"
    sock.sendall(req.encode())
    
    resp = b""
    while True:
        try:
            chunk = sock.recv(65536)
            if not chunk: break
            resp += chunk
        except: break
    sock.close()
    
    # Find JPEG in response body
    body = resp.split(b"\r\n\r\n", 1)[1] if b"\r\n\r\n" in resp else resp
    # Dechunk if needed
    if b"Transfer-Encoding: chunked" in resp.split(b"\r\n\r\n")[0]:
        dechunked = b""
        pos = 0
        while pos < len(body):
            nl = body.find(b"\r\n", pos)
            if nl == -1: break
            try:
                size = int(body[pos:nl], 16)
            except: break
            if size == 0: break
            dechunked += body[nl+2:nl+2+size]
            pos = nl + 2 + size + 2
        body = dechunked
    
    i = 0
    while i < len(body) - 9:
        if body[i] == 0xFF and body[i+1] == 0xC0:
            h = struct.unpack(">H", body[i+5:i+7])[0]
            w = struct.unpack(">H", body[i+7:i+9])[0]
            print(f"  JPEG: {w}x{h} ({len(body)} bytes)")
            break
        i += 1
    
    # Delete
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(5)
    sock.connect((SCANNER, PORT))
    sock.sendall(f"DELETE {location.replace(f'http://{SCANNER}', '')} HTTP/1.1\r\nHost: {SCANNER}\r\nConnection: close\r\n\r\n".encode())
    sock.recv(4096)
    sock.close()

# Test WITHOUT TCP_NODELAY (like Python/Java default)
http_exchange("Without TCP_NODELAY", use_nodelay=False)

# Test WITH TCP_NODELAY (like curl/libcurl)
http_exchange("With TCP_NODELAY", use_nodelay=True)
