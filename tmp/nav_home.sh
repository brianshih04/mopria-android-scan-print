#!/bin/bash
export PATH="$PATH:/c/Users/av00021/AppData/Local/Android/Sdk/platform-tools"

# Wait for app to settle
sleep 2

# Go to Home tab
adb shell input tap 127 2211
sleep 1

# Check we're on home
adb shell uiautomator dump /sdcard/ui_dump.xml
adb pull /sdcard/ui_dump.xml "C:/Projects/mopria-android-scan-print/tmp/ui_nav.xml"

# Check if in Real mode - look for "Physical devices" or "Working"
python -c "
import xml.etree.ElementTree as ET
tree = ET.parse(r'C:\Projects\mopria-android-scan-print\tmp\ui_nav.xml')
root = tree.getroot()
texts = [(n.get('text','').strip(), n.get('bounds','')) for n in root.findall('.//node') if n.get('text','').strip()]
for t, b in texts:
    print(f'  {t} @ {b}')
"
