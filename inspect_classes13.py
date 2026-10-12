import struct

with open("classes13_orig.dex", "rb") as f:
    data = f.read()

string_ids_size = struct.unpack("<I", data[0x38:0x3c])[0]
string_ids_off = struct.unpack("<I", data[0x3c:0x40])[0]

def get_str(idx):
    str_off = struct.unpack("<I", data[string_ids_off + idx*4 : string_ids_off + (idx+1)*4])[0]
    p = str_off
    while data[p] & 0x80:
        p += 1
    p += 1
    end = data.find(b"\x00", p)
    return data[p:end].decode("utf-8", errors="replace")

type_ids_size = struct.unpack("<I", data[0x40:0x44])[0]
type_ids_off = struct.unpack("<I", data[0x44:0x48])[0]
def get_type(idx):
    str_idx = struct.unpack("<I", data[type_ids_off + idx*4 : type_ids_off + (idx+1)*4])[0]
    return get_str(str_idx)

class_defs_size = struct.unpack("<I", data[0x60:0x64])[0]
class_defs_off = struct.unpack("<I", data[0x64:0x68])[0]

for i in range(class_defs_size):
    off = class_defs_off + i*32
    class_idx = struct.unpack("<I", data[off:off+4])[0]
    class_name = get_type(class_idx)
    if "mixing" in class_name or "CarLife" in class_name or "Cast" in class_name or "vivo" in class_name:
        print(i, class_name)
