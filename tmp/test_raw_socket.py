#!/usr/bin/env python3
"""Test what curl does differently - use raw socket to replicate curl's exact HTTP request."""
import socket
import struct
import time

SCANNER = "10.1.121.175"
PORT = 80

# Send EXACTLY what curl sends (minimal HTTP/1.1)
def http_request(method, path, body=None, extra_headers=""):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(120)
    sock.connect((SCANNER, PORT))
    
    request = f"{method} {path} HTTP/1.1\r\nHost: {SCANNER}\r\nUser-Agent: curl/8.21.0\r\nAccept: */*{extra_headers}\r\n"
    if body:
        request += f"Content-Type: text/xml; charset=utf-8\r\nContent-Length: {len(body)}\r\n"
    request += "Connection: close\r\n\r\n"
    if body:
        request_bytes = request.encode() + body
    else:
        request_bytes = request.encode()
    
    sock.sendall(request_bytes)
    
    # Read full response
    response = b""
    while True:
        try:
            chunk = sock.recv(65536)
            if not chunk:
                break
            response += chunk
        except socket.timeout:
            break
    sock.close()
    return response

# Parse HTTP response
def parse_response(raw):
    header_end = raw.find(b"\r\n\r\n")
    header_part = raw[:header_end].decode("utf-8", errors="replace")
    body = raw[header_end+4:]
    
    lines = header_part.split("\r\n")
    status_line = lines[0]
    status_code = int(status_line.split()[1])
    
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    
    # Handle chunked encoding
    if headers.get("transfer-encoding") == "chunked":
        body = dechunk(body)
    
    return status_code, headers, body

def dechunk(data):
    result = b""
    pos = 0
    while pos < len(data):
        line_end = data.find(b"\r\n", pos)
        if line_end == -1:
            break
        size_str = data[pos:line_end].decode("ascii", errors="replace").strip()
        try:
            chunk_size = int(size_str, 16)
        except:
            break
        if chunk_size == 0:
            break
        result += data[line_end+2:line_end+2+chunk_size]
        pos = line_end + 2 + chunk_size + 2
    return result

# 1. Status check
print("=== Status ===")
raw = http_request("GET", "/eSCL/ScannerStatus")
code, headers, body = parse_response(raw)
print(f"Status: {code}")

# 2. POST scan job  
print("\n=== POST ScanJobs (curl-style raw socket) ===")
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

raw = http_request("POST", "/eSCL/ScanJobs", body=xml)
code, headers, body = parse_response(raw)
location = headers.get("location", "")
print(f"POST: {code}, Location: {location}")

time.sleep(3)

# 3. GET NextDocument
print("\n=== NextDocument (curl-style raw socket) ===")
job_path = location.replace(f"http://{SCANNER}", "")
raw = http_request("GET", f"{job_path}/NextDocument")
code, headers, body = parse_response(raw)
print(f"NextDocument: {code}, Content-Type: {headers.get('content-type')}, size: {len(body)}")

# Parse JPEG dimensions
i = 0
while i < len(body) - 9:
    if body[i] == 0xFF and body[i+1] == 0xC0:
        h = struct.unpack(">H", body[i+5:i+7])[0]
        w = struct.unpack(">H", body[i+7:i+9])[0]
        print(f"JPEG dimensions: {w}x{h}")
        break
    i += 1

# 4. Delete
raw = http_request("DELETE", job_path)
print(f"DELETE: OK")

print(f"\ncurl reference: 2448x3470 (524KB)")
print(f"Python http.client: 1680x2193 (62KB)")
