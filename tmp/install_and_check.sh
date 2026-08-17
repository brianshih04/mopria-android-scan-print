#!/bin/bash
export PATH="$PATH:/c/Users/av00021/AppData/Local/Android/Sdk/platform-tools"
adb install -r "C:/Projects/mopria-android-scan-print/app/build/outputs/apk/debug/app-debug.apk" 2>&1
echo "---"
adb shell am start -n com.brianshih.mopria.android.scanprint/.MainActivity 2>&1
sleep 3
echo "ADF: $(curl.exe -s -m 5 'http://10.1.121.175/eSCL/ScannerStatus' 2>/dev/null | grep -o 'ScannerAdf[A-Za-z]*')"
