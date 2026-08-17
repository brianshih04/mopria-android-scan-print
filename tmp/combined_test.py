#!/usr/bin/env python3
"""
Combined test: curl baseline + PowerShell .NET + Python raw socket.
Each test consumes 1 paper from ADF. Requires 3 papers loaded.
"""
import subprocess
import struct
import time
import sys

SCANNER = "10.1.121.175"
XML_FILE = r"C:\Projects\mopria-android-scan-print\tmp\adf_test_scan_settings.xml"

def get_dims(filepath):
    with open(filepath, "rb") as f:
        data = f.read()
    i = 0
    while i < len(data) - 9:
        if data[i] == 0xFF and data[i+1] == 0xC0:
            h = struct.unpack(">H", data[i+5:i+7])[0]
            w = struct.unpack(">H", data[i+7:i+9])[0]
            return f"{w}x{h} ({len(data):,} bytes)"
        i += 1
    return f"? ({len(data)} bytes)"

def check_adf():
    r = subprocess.run(["curl.exe", "-s", "-m", "5", f"http://{SCANNER}/eSCL/ScannerStatus"],
                       capture_output=True, text=True, timeout=10)
    return "ScannerAdfLoaded" in r.stdout

# ── Test 1: curl baseline ──
print("=" * 60)
print("TEST 1: curl (baseline)")
print("=" * 60)
if not check_adf():
    print("ADF empty! Stopping."); sys.exit(1)

# POST
r = subprocess.run(["curl.exe", "-s", "-m", "30", "-D", "-",
    "-H", "Content-Type: text/xml; charset=utf-8",
    "-d", f"@{XML_FILE}", "-o", "NUL",
    f"http://{SCANNER}/eSCL/ScanJobs"], capture_output=True, text=True, timeout=30)
loc = next((l.split(":",1)[1].strip() for l in r.stdout.split("\n") if l.lower().startswith("location:")), None)
print(f"  POST: {loc}")
time.sleep(3)
# GET
out1 = r"C:\Projects\mopria-android-scan-print\tmp\result_curl.jpg"
subprocess.run(["curl.exe", "-s", "-m", "120", "-o", out1, f"{loc}/NextDocument"],
               capture_output=True, timeout=120)
print(f"  Result: {get_dims(out1)}")
subprocess.run(["curl.exe", "-s", "-m", "5", "-X", "DELETE", loc], capture_output=True, timeout=10)

# ── Test 2: PowerShell .NET HttpClient ──
print("\n" + "=" * 60)
print("TEST 2: PowerShell Invoke-WebRequest (.NET)")
print("=" * 60)
if not check_adf():
    print("ADF empty! Stopping."); sys.exit(1)

ps_script = f'''
$xml = Get-Content "{XML_FILE}" -Raw -Encoding UTF8
$bytes = [System.Text.Encoding]::UTF8.GetBytes($xml)
$resp = Invoke-WebRequest -Uri "http://{SCANNER}/eSCL/ScanJobs" -Method POST -ContentType "text/xml; charset=utf-8" -Body $bytes -TimeoutSec 30
$loc = $resp.Headers.Location
Write-Host "POST: $loc"
Start-Sleep -Seconds 5
$img = Invoke-WebRequest -Uri "$loc/NextDocument" -Method GET -TimeoutSec 120
[System.IO.File]::WriteAllBytes("C:\\Projects\\mopria-android-scan-print\\tmp\\result_dotnet.jpg", $img.Content)
$b = [byte[]]$img.Content
for ($i=0; $i -lt $b.Length-9; $i++) {{
    if ($b[$i] -eq 0xFF -and $b[$i+1] -eq 0xC0) {{
        $h = ($b[$i+5] -shl 8) -bor $b[$i+6]
        $w = ($b[$i+7] -shl 8) -bor $b[$i+8]
        Write-Host "Result: ${{w}}x${{h}} ($($img.Content.Length) bytes)"
        break
    }}
}}
try {{ Invoke-WebRequest -Uri $loc -Method DELETE -TimeoutSec 5 }} catch {{}}
'''
r = subprocess.run(["powershell", "-NoProfile", "-Command", ps_script],
                   capture_output=True, text=True, timeout=120)
print(f"  {r.stdout.strip()}")
if r.stderr.strip():
    print(f"  STDERR: {r.stderr.strip()[:200]}")

# ── Test 3: Python raw socket (exact curl bytes, TCP_NODELAY, single sendall) ──
print("\n" + "=" * 60)
print("TEST 3: Python raw socket (TCP_NODELAY, single sendall)")
print("=" * 60)
if not check_adf():
    print("ADF empty! Stopping."); sys.exit(1)

import socket

xml_bytes = open(XML_FILE, "rb").read()

# POST
sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
sock.settimeout(30)
sock.connect((SCANNER, 80))
post_req = (
    b"POST /eSCL/ScanJobs HTTP/1.1\r\n"
    b"Host: 10.1.121.175\r\n"
    b"User-Agent: curl/8.21.0\r\n"
    b"Accept: */*\r\n"
    b"Content-Type: text/xml; charset=utf-8\r\n"
    b"Content-Length: " + str(len(xml_bytes)).encode() + b"\r\n"
    b"Connection: close\r\n\r\n"
) + xml_bytes
sock.sendall(post_req)  # single send
resp = b""
while b"\r\n\r\n" not in resp:
    chunk = sock.recv(4096)
    if not chunk: break
    resp += chunk
sock.close()
loc3 = None
for line in resp.split(b"\r\n"):
    if line.lower().startswith(b"location:"):
        loc3 = line.split(b":",1)[1].strip().decode()
        break
print(f"  POST: {loc3}")

time.sleep(5)

# GET
sock2 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock2.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
sock2.settimeout(120)
sock2.connect((SCANNER, 80))
path = loc3.replace(f"http://{SCANNER}", "") + "/NextDocument"
get_req = f"GET {path} HTTP/1.1\r\nHost: {SCANNER}\r\nUser-Agent: curl/8.21.0\r\nAccept: */*\r\nConnection: close\r\n\r\n".encode()
sock2.sendall(get_req)
data = b""
while True:
    try:
        chunk = sock2.recv(65536)
        if not chunk: break
        data += chunk
    except: break
sock2.close()

# Parse
header_end = data.find(b"\r\n\r\n")
body = data[header_end+4:]
header_str = data[:header_end].decode("utf-8", errors="replace")
if "chunked" in header_str.lower():
    dechunked = b""
    pos = 0
    while pos < len(body):
        nl = body.find(b"\r\n", pos)
        if nl < 0: break
        try: size = int(body[pos:nl], 16)
        except: break
        if size == 0: break
        dechunked += body[nl+2:nl+2+size]
        pos = nl + 2 + size + 2
    body = dechunked

out3 = r"C:\Projects\mopria-android-scan-print\tmp\result_rawsocket.jpg"
with open(out3, "wb") as f:
    f.write(body)
print(f"  Result: {get_dims(out3)}")

# Delete
sock3 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock3.settimeout(5)
sock3.connect((SCANNER, 80))
sock3.sendall(f"DELETE {loc3.replace(f'http://{SCANNER}','')} HTTP/1.1\r\nHost: {SCANNER}\r\nConnection: close\r\n\r\n".encode())
sock3.recv(4096)
sock3.close()

print("\n" + "=" * 60)
print("DONE")
print("=" * 60)
