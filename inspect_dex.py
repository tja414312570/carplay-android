import struct

with open("classes6_orig.dex", "rb") as f:
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

method_ids_size = struct.unpack("<I", data[0x58:0x5c])[0]
method_ids_off = struct.unpack("<I", data[0x5c:0x60])[0]
def get_method(idx):
    off = method_ids_off + idx*8
    class_idx, proto_idx, name_idx = struct.unpack("<HHI", data[off:off+8])
    return get_type(class_idx) + "->" + get_str(name_idx)

print("Type 0x48:", get_type(0x48))
print("Method 0x22:", get_method(0x22))
print("Method 0xcf:", get_method(0xcf))
print("Method 0xcd:", get_method(0xcd))
print("Method 0xc8:", get_method(0xc8))
