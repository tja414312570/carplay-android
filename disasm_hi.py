import zipfile, struct

with zipfile.ZipFile('jovi_networking.apk', 'r') as z:
    dex = z.read('classes5.dex')

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

def get_method_name(idx):
    if idx >= method_ids_size: return f'method_{idx}'
    c_idx, p_idx, n_idx = struct.unpack('<HHI', dex[method_ids_off + idx*8 : method_ids_off + (idx+1)*8])
    return get_type_str(c_idx) + '->' + get_str(n_idx)

# Let's inspect Method I (P2p-connectGroup) and Method H
# We can find their offsets from inspect_p2p_methods
# Let's write a targeted disassembler for WifiP2pService->I and WifiP2pService->H
from inspect_p2p_methods import methods

for name, off in methods:
    if name.endswith('->I') or name.endswith('->H'):
        print(f'=== Disassembling {name} at {hex(off)} ===')
        regs, ins, outs, tries, debug, insns_sz = struct.unpack('<HHHHII', dex[off:off+16])
        insns = dex[off+16:off+16+insns_sz*2]
        i = 0
        while i < len(insns):
            op = insns[i]
            if op == 0x1a:
                s_idx = struct.unpack('<H', insns[i+2:i+4])[0]
                print(f'  [{hex(i)}] const-string v{insns[i+1]}, "{get_str(s_idx)}"')
                i += 4
            elif op in [0x6e, 0x6f, 0x70, 0x71, 0x72]:
                m_idx = struct.unpack('<H', insns[i+2:i+4])[0]
                print(f'  [{hex(i)}] invoke-{hex(op)} {get_method_name(m_idx)}')
                i += 6
            else:
                i += 2
