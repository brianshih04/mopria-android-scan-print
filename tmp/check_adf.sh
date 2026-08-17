#!/bin/bash
export PATH="$PATH:/c/Users/av00021/AppData/Local/Android/Sdk/platform-tools"
echo "ADF: $(curl.exe -s -m 3 'http://10.1.121.175/eSCL/ScannerStatus' 2>/dev/null | grep -o 'ScannerAdf[A-Za-z]*')"
