#!/usr/bin/env python3
"""Test PowerShell .NET Invoke-WebRequest (1 paper)."""
import subprocess
import time

SCANNER = "10.1.121.175"
XML_FILE = r"C:\Projects\mopria-android-scan-print\tmp\adf_test_scan_settings.xml"

ps_script = f'''
$ErrorActionPreference = "Stop"
$xml = [System.IO.File]::ReadAllBytes("{XML_FILE}")
$resp = Invoke-WebRequest -Uri "http://{SCANNER}/eSCL/ScanJobs" -Method POST -ContentType "text/xml; charset=utf-8" -Body $xml -TimeoutSec 30
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
        Write-Host "JPEG: ${{w}}x${{h}} ($($img.Content.Length) bytes)"
        break
    }}
}}
try {{ Invoke-WebRequest -Uri $loc -Method DELETE -TimeoutSec 5 }} catch {{}}
'''

r = subprocess.run(["powershell", "-NoProfile", "-Command", ps_script],
                   capture_output=True, text=True, timeout=120)
print(".NET HttpClient result:")
print(r.stdout.strip())
if r.stderr.strip():
    print(f"STDERR: {r.stderr.strip()[:300]}")
