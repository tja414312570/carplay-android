import zipfile, struct

with zipfile.ZipFile('service-wifi.jar', 'r') as z:
    dex = z.read('classes.dex')

type_ids_size, type_ids_off = struct.unpack('<II', dex[0x40:0x48])
string_ids_size, string_ids_off = struct.unpack('<II', dex[0x38:0x40])
method_ids_size, method_ids_off = struct.unpack('<II', dex[0x58:0x60])
class_defs_size, class_defs_off = struct.unpack('<II', dex[0x60:0x68])

def get_str(idx):
    off = struct.unpack('<I', dex[string_ids_off + idx*4 : string_ids_off + (idx+1)*4])[0]
    p = off
    while dex[p] & 0x80: p += 1
    p += 1
    return dex[p:].split(b'\x00')[0].decode('utf-8', errors='ignore')

def get_type_str(idx):
    s_idx = struct.unpack('<I', dex[type_ids_off + idx*4 : type_ids_off + (idx+1)*4])[0]
    return get_str(s_idx)

def get_method_name(idx):
    c_idx, p_idx, n_idx = struct.unpack('<HHI', dex[method_ids_off + idx*8 : method_ids_off + (idx+1)*8])
    return get_type_str(c_idx) + '->' + get_str(n_idx)

pos = 0xf5a3c
# Disassemble around pos
start = pos - 100
end = pos + 150
i = start
while i < end:
    op = dex[i]
    if op == 0x1a:
        s_idx = struct.unpack('<H', dex[i+2:i+4])[0]
        print(f'  [{hex(i)}] const-string v{dex[i+1]}, "{get_str(s_idx)}"')
        i += 4
    elif op in [0x6e, 0x6f, 0x70, 0x71, 0x72]:
        mid = struct.unpack('<H', dex[i+2:i+4])[0]
        print(f'  [{hex(i)}] invoke-{hex(op)} {get_method_name(mid)}')
        i += 6
    else:
        i += 2
