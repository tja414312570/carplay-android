import zipfile
import json
import glob

files = glob.glob("H:/iccoa-license-manager/runtime/log-uploads/carplay-*/*.zip")
# find 00a5ff50
target = [f for f in files if "00a5ff50" in f][0]

with zipfile.ZipFile(target) as z:
    for n in z.namelist():
        if n.endswith(".json"):
            d = json.loads(z.read(n))
            events = d.get("events", [])
            for evt in events:
                ms = evt.get("offsetMs")
                lvl = evt.get("level")
                msg = evt.get("message", "")
                if ms and ms > 15000:
                    print(f"{ms}ms [{lvl}] {msg}")
