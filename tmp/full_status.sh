#!/bin/bash
export PATH="$PATH:/c/Users/av00021/AppData/Local/Android/Sdk/platform-tools"
echo "ADF: $(curl.exe -s -m 3 'http://10.1.121.175/eSCL/ScannerStatus' 2>/dev/null | grep -o 'ScannerAdf[A-Za-z]*')"
echo "---"
# Full status to see all job states
curl.exe -s -m 5 "http://10.1.121.175/eSCL/ScannerStatus" 2>/dev/null | python -c "
import sys, xml.etree.ElementTree as ET
data = sys.stdin.read()
ns = {'scan': 'http://schemas.hp.com/imaging/escl/2011/05/03', 'pwg': 'http://www.pwg.org/schemas/2010/12/sm'}
root = ET.fromstring(data)
state = root.find('pwg:State', ns)
adf = root.find('scan:AdfState', ns)
print(f'Scanner: {state.text} ADF: {adf.text}')
print('Recent 5 jobs:')
for job in root.findall('.//scan:JobInfo', ns)[:5]:
    uri = job.find('pwg:JobUri', ns)
    jstate = job.find('pwg:JobState', ns)
    reason = job.find('pwg:JobStateReasons/pwg:JobStateReason', ns)
    completed = job.find('pwg:ImagesCompleted', ns)
    to_transfer = job.find('pwg:ImagesToTransfer', ns)
    if uri is not None:
        jobid = uri.text.split('/')[-1][:20]
        print(f'  {jobid} state={jstate.text} reason={reason.text if reason is not None else \"?\"} completed={completed.text if completed is not None else \"?\"} to_transfer={to_transfer.text if to_transfer is not None else \"?\"}')
"
