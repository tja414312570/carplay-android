import zipfile
import json
import glob
import sys

files = glob.glob("H:/iccoa-license-manager/runtime/log-uploads/carplay-*/*.zip")
files.sort(key=lambda x: -len(x))
latest_files = sorted(files, reverse=True)[:5]

for f in latest_files:
    print("=" * 60)
    print("FILE:", f)
    with zipfile.ZipFile(f) as z:
        for n in z.namelist():
            if n.endswith(".json"):
                data = json.loads(z.read(n))
                stage = data.get("stage")
                print("Stage:", stage)
                for evt in data.get("events", []):
                    msg = evt.get("message", "")
                    if any(k in msg for k in ["vivo", "宿主", "prepareCast", "Not yet implemented", "0x4300", "0x4301", "组件", "Cast"]):
                        print(f"{evt.get('offsetMs')}ms [{evt.get('level')}] {msg}")
