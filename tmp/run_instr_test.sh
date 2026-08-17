#!/bin/bash
export PATH="$PATH:/c/Users/av00021/AppData/Local/Android/Sdk/platform-tools"
echo "ADF: $(curl.exe -s -m 3 'http://10.1.121.175/eSCL/ScannerStatus' 2>/dev/null | grep -o 'ScannerAdf[A-Za-z]*')"
echo "---"
adb logcat -c
adb shell am instrument -w -e class com.brianshih.mopria.android.scanprint.AdfScanDiagnosisTest com.brianshih.mopria.android.scanprint.test/androidx.test.runner.AndroidJUnitRunner 2>&1
echo "=== LOGCAT ==="
adb logcat -d -v time 2>&1 | grep "AdfDiagnosis"
