"""Resolve focused ARM ELF literal references offline. Never runs target code."""
from pathlib import Path
import argparse
import hashlib
import json
import struct

BASE = 0x10000
SPECS = {
    'setupdata': {
        'file': 'live/files/vendor_bin_setupdata',
        'sha256': '98c4b1bc0712d793c0d9adf100ffa2a8aecd47d8776f66c196d59bb97a2be0ef',
        'references': [(0x11a58,0x11a1e),(0x11a5c,0x11a20),
                       (0x11c20,0x11af8),(0x11c24,0x11afa),(0x11c28,0x11b00),
                       (0x11c2c,0x11b06),(0x11c30,0x11b0c),(0x11c34,0x11b12),
                       (0x11c38,0x11b18),(0x11c3c,0x11b1e),(0x11c40,0x11b24),
                       (0x1203c,0x11f14),(0x12040,0x11f2a),(0x12044,0x11f2c),
                       (0x1204c,0x11f6c),(0x12050,0x11f82),(0x12054,0x11f84)]},
    'init': {
        'file': 'init-acquisition/system_bin_init',
        'sha256': 'a9686d8137c048a0592ac8f071f8ea97e45cd1899467fea3f00d8b289a2c9867',
        'references': [(0xf94ec,0xf94ae),(0xf94f4,0xf94b6),(0xf7bbc,0xf78d2),
                       (0xf8da4,0xf8a34),(0xf16a0,0xf13b0),(0xf169c,0xf1394),
                       (0xf16bc,0xf14ae),(0xf16c4,0xf14f2),(0xf16c8,0xf1500),
                       (0xf16cc,0xf150c),(0xf16d0,0xf1518),(0xf16d8,0xf15b2),
                       (0xf2a90,0xf2944),(0xf2a94,0xf2994),(0xf2a98,0xf2a00),
                       (0xf2a9c,0xf2a40),(0xf2e8c,0xf2ad4),(0xf2e90,0xf2ad6)]}
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage',type=Path)
    parser.add_argument('kind',choices=SPECS)
    parser.add_argument('output',type=Path)
    args = parser.parse_args()
    if args.output.exists(): raise SystemExit('Refusing to overwrite')
    spec = SPECS[args.kind]
    data = (args.stage/spec['file']).read_bytes()
    assert hashlib.sha256(data).hexdigest() == spec['sha256']
    assert data[:6] == b'\x7fELF\x01\x01', 'Requires ELF32 little-endian'
    phoff = struct.unpack_from('<I',data,28)[0]
    entsize,count = struct.unpack_from('<HH',data,42)
    segments=[]
    for i in range(count):
        t,off,va,pa,size,memsz,flags,align = struct.unpack_from('<8I',data,phoff+i*entsize)
        if t == 1: segments.append((va+BASE,off,size))

    def raw(address,size):
        for start,offset,length in segments:
            if start <= address and address+size <= start+length:
                return data[offset+address-start:offset+address-start+size]
        raise ValueError('Not file-backed: '+hex(address))

    def word(address): return struct.unpack('<I',raw(address,4))[0]

    def string(address):
        result=bytearray()
        try:
            for i in range(220):
                char=raw(address+i,1)[0]
                if not char: return result.decode('ascii','replace')
                result.append(char)
        except ValueError: return '<not file-backed>'
        return '<unterminated>'

    rows=[]
    for pool,pc in spec['references']:
        address=(word(pool)+pc)&0xffffffff
        row={'pool':hex(pool),'pc_addend':hex(pc),'resolved':hex(address),
             'string':string(address)}
        if args.kind == 'setupdata' and pool == 0x11c20:
            row['table_entries']=[{'unrelocated_word':hex(word(address+4*i)),
                                   'string':string(word(address+4*i)+BASE)} for i in range(2)]
        if args.kind == 'init' and pool in (0xf94f4,0xf7bbc):
            row['got_unrelocated_word']=hex(word(address))
            row['got_after_base_relocation']=hex(word(address)+BASE)
        rows.append(row)
    result={'sha256':spec['sha256'],'image_base':hex(BASE),'rows':rows,
            'method':'PT_LOAD mapping; PC addends from retained disassembly/decompilation; explicit base adjustment for ELF table pointers'}
    with args.output.open('x',encoding='utf-8') as stream:
        json.dump(result,stream,indent=2)
        stream.write('\n')
    print(json.dumps(result,indent=2))


if __name__ == '__main__':
    main()
