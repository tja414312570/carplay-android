import zipfile, struct

with zipfile.ZipFile('jovi_networking.apk', 'r') as z:
    dex = z.read('classes3.dex')

code_off = 0x1760a2 - 58
regs, ins, outs, tries, debug, insns_sz = struct.unpack('<HHHHII', dex[code_off:code_off+16])
print(f'code_off: {hex(code_off)}, regs: {regs}, ins: {ins}, insns_sz: {insns_sz}')
insns = dex[code_off+16:code_off+16+insns_sz*2]

type_ids_size, type_ids_off = struct.unpack('<II', dex[0x40:0x48])
method_ids_size, method_ids_off = struct.unpack('<II', dex[0x58:0x60])
string_ids_size, string_ids_off = struct.unpack('<II', dex[0x38:0x40])

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
