import zipfile, struct

with zipfile.ZipFile('jovi_networking.apk', 'r') as z:
    dex = z.read('classes5.dex')

def read_uleb128(p):
    res = 0; shift = 0
    while True:
        b = dex[p]; p += 1
        res |= (b & 0x7f) << shift
        if (b & 0x80) == 0: break
        shift += 7
    return res, p

type_ids_size, type_ids_off = struct.unpack('<II', dex[0x40:0x48])
string_ids_size, string_ids_off = struct.unpack('<II', dex[0x38:0x40])
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

# Find class Lcom/vivo/ucar/connect/wireless/WifiP2pService;
target_class = 'Lcom/vivo/ucar/connect/wireless/WifiP2pService;'
target_c_def = None
for c_i in range(class_defs_size):
    c_def_off = class_defs_off + c_i * 32
    class_idx, _, _, _, _, _, data_off, _ = struct.unpack('<IIIIIIII', dex[c_def_off:c_def_off+32])
    if get_type_str(class_idx) == target_class:
        target_c_def = data_off
        break

print('WifiP2pService class_data_off:', hex(target_c_def))

# Read all methods of WifiP2pService
p = target_c_def
sf_sz, p = read_uleb128(p); if_sz, p = read_uleb128(p)
dm_sz, p = read_uleb128(p); vm_sz, p = read_uleb128(p)
for _ in range(sf_sz + if_sz):
    _, p = read_uleb128(p); _, p = read_uleb128(p)

methods = []
m_idx = 0
for _ in range(dm_sz):
    diff, p = read_uleb128(p); m_idx += diff
    acc, p = read_uleb128(p); code_off, p = read_uleb128(p)
    methods.append((get_method_name(m_idx), code_off))

m_idx = 0
for _ in range(vm_sz):
    diff, p = read_uleb128(p); m_idx += diff
    acc, p = read_uleb128(p); code_off, p = read_uleb128(p)
    methods.append((get_method_name(m_idx), code_off))

print(f'Total methods: {len(methods)}')
for name, off in methods:
    if off == 0: continue
    # scan strings in code
    regs, ins, outs, tries, debug, insns_sz = struct.unpack('<HHHHII', dex[off:off+16])
    insns = dex[off+16:off+16+insns_sz*2]
    # check if has '1002' or 'connect'
    has_1002 = False
    i = 0
    while i < len(insns):
        op = insns[i]
        if op == 0x1a:
            s_idx = struct.unpack('<H', insns[i+2:i+4])[0]
            s = get_str(s_idx)
            if '1002' in s or 'connect' in s.lower() or 'apgc' in s.lower():
                print(f'  Method {name} has interesting string: {s}')
            i += 4
        elif op in [0x6e, 0x6f, 0x70, 0x71, 0x72]:
            i += 6
        else:
            i += 2
