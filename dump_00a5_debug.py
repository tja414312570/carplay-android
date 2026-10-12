import zipfile
import json
import glob

files = glob.glob("H:/iccoa-license-manager/runtime/log-uploads/carplay-*/*.zip")
target = [f for f in files if "00a5ff50" in f][0]

with zipfile.ZipFile(target) as z:
    for n in z.namelist():
        print("Entry:", n)
        if n.endswith(".json"):
            d = json.loads(z.read(n))
            print("Keys:", d.keys())
            events = d.get("events", [])
            print("Total events:", len(events))
            for evt in events[-25:]:
                print(evt)
