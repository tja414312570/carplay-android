import struct
import inspect_dex

with open("classes6_orig.dex", "rb") as f:
    data = f.read()

off = 0x4470 # start lambda 22
reg_size, ins_size, outs_size, tries_size, debug_off, insns_size = struct.unpack("<HHHHII", data[off:off+16])
print("regs:", reg_size, "ins:", ins_size, "outs:", outs_size)
p = off + 16
end = p + insns_size*2
while p < end:
    op = data[p]
    if op in (0x6e, 0x6f, 0x70, 0x71, 0x72):
        meth_idx = struct.unpack("<H", data[p+2:p+4])[0]
        vA = data[p+1] >> 4
        print(hex(p-off-16), "invoke", inspect_dex.get_method(meth_idx))
        p += 6
    elif op in (0x1a, 0x1b):
        str_idx = struct.unpack("<H", data[p+2:p+4])[0]
        print(hex(p-off-16), "str", inspect_dex.get_str(str_idx))
        p += 4
    elif op in (0x52, 0x53, 0x54):
        field_idx = struct.unpack("<H", data[p+2:p+4])[0]
        print(hex(p-off-16), "iget", hex(field_idx))
        p += 4
    else:
        p += 2
