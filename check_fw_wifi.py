import zipfile, struct

with zipfile.ZipFile('fw-wifi.jar', 'r') as z:
    dex = z.read('classes.dex')

string_ids_size, string_ids_off = struct.unpack('<II', dex[0x38:0x40])
type_ids_size, type_ids_off = struct.unpack('<II', dex[0x40:0x48])
method_ids_size, method_ids_off = struct.unpack('<II', dex[0x58:0x60])
class_defs_size, class_defs_off = struct.unpack('<II', dex[0x60:0x68])

def get_str(idx):
    if idx >= string_ids_size: return f'str_{idx}'
    off = struct.unpack('<I', dex[string_ids_off + idx*4 : string_ids_off + (idx+1)*4])[0]
    p = off
    while dex[p] & 0x80: p += 1
    p += 1
    return dex[p:].split(b'\x00')[0].decode('utf-8', errors='ignore')

def get_type_str(idx):
    if idx >= type_ids_size: return f'type_{idx}'
    s_idx = struct.unpack('<I', dex[type_ids_off + idx*4 : type_ids_off + (idx+1)*4])[0]
    return get_str(s_idx)

# Search for isApGcSupported in fw-wifi.jar
found = False
for i in range(string_ids_size):
    s = get_str(i)
    if 'isApGcSupported' in s:
        print('Found string in fw-wifi:', i, s)
        found = True

if not found:
    print('isApGcSupported not in fw-wifi.jar string pool')
