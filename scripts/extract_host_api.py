#!/usr/bin/env python3
"""Offline DEX signature/annotation inventory. Does not run the APK or send requests."""
import argparse
import gzip
import hashlib
import json
import struct
import zipfile
from pathlib import Path


class Dex:
    def __init__(self, data):
        self.b = data
        if data[:4] != b'dex\n':
            raise ValueError('Not a standard DEX')
        self.strings = [self.string(self.u32(self.u32(60) + 4*i)) for i in range(self.u32(56))]
        self.types = [self.strings[self.u32(self.u32(68) + 4*i)] for i in range(self.u32(64))]
        self.protos = []
        for i in range(self.u32(72)):
            off = self.u32(76) + i*12
            p = self.u32(off+8)
            params = [self.types[self.u16(p+4+j*2)] for j in range(self.u32(p))] if p else []
            self.protos.append((params, self.types[self.u32(off+4)]))
        self.ids = []
        for i in range(self.u32(88)):
            off = self.u32(92) + i*8
            owner = self.types[self.u16(off)]
            params, ret = self.protos[self.u16(off+2)]
            name = self.strings[self.u32(off+4)]
            self.ids.append((owner, name, params, ret))

    def u16(self, p):
        return struct.unpack_from('<H', self.b, p)[0]

    def u32(self, p):
        return struct.unpack_from('<I', self.b, p)[0]

    def uleb(self, p):
        v = shift = 0
        while True:
            x = self.b[p]; p += 1; v |= (x & 127) << shift
            if not x & 128:
                return v, p
            shift += 7
            if shift > 35:
                raise ValueError('Bad ULEB128')

    def string(self, p):
        _, p = self.uleb(p)
        end = self.b.index(0, p)
        return self.b[p:end].replace(b'\xc0\x80', b'\x00').decode('utf-8', 'replace')

    def annotation(self, p):
        t, p = self.uleb(p); size, p = self.uleb(p); values = {}
        for _ in range(size):
            name, p = self.uleb(p); value, p = self.value(p); values[self.strings[name]] = value
        return {'type': self.types[t], 'values': values}, p

    def value(self, p):
        tag = self.b[p]; p += 1; typ = tag & 31; n = (tag >> 5) + 1
        if typ == 0x1c:
            size, p = self.uleb(p); out = []
            for _ in range(size):
                v, p = self.value(p); out.append(v)
            return out, p
        if typ == 0x1d:
            return self.annotation(p)
        if typ == 0x1e:
            return None, p
        if typ == 0x1f:
            return bool(tag >> 5), p
        raw = int.from_bytes(self.b[p:p+n], 'little'); p += n
        if typ == 0x17:
            return self.strings[raw], p
        if typ == 0x18:
            return self.types[raw], p
        return {'encodedType': typ, 'raw': raw}, p

    def annotations(self, p):
        if not p:
            return []
        return [self.annotation(self.u32(p+4+i*4)+1)[0] for i in range(self.u32(p))]

    def methods(self):
        for i in range(self.u32(96)):
            p = self.u32(100) + i*32
            owner = self.types[self.u32(p)]; flags = self.u32(p+4)
            a = self.u32(p+20); data = self.u32(p+24); ma = {}; pa = {}
            if a:
                fields, nm, np = self.u32(a+4), self.u32(a+8), self.u32(a+12)
                q = a+16+fields*8
                for _ in range(nm):
                    ma[self.u32(q)] = self.annotations(self.u32(q+4)); q += 8
                for _ in range(np):
                    mid, lst = self.u32(q), self.u32(q+4); q += 8
                    pa[mid] = [self.annotations(self.u32(lst+4+j*4)) for j in range(self.u32(lst))] if lst else []
            if not data:
                continue
            counts = []
            for _ in range(4):
                n, data = self.uleb(data); counts.append(n)
            for _ in range(counts[0]+counts[1]):
                _, data = self.uleb(data); _, data = self.uleb(data)
            for size in counts[2:]:
                idx = 0
                for _ in range(size):
                    delta, data = self.uleb(data); idx += delta
                    access, data = self.uleb(data); code, data = self.uleb(data)
                    cls, name, params, ret = self.ids[idx]
                    entry = {'descriptor': cls+'->'+name+'('+''.join(params)+')'+ret, 'owner': cls,
                             'name': name, 'parameters': params, 'returnType': ret, 'accessFlags': access,
                             'interface': bool(flags & 0x200), 'native': bool(access & 0x100)}
                    if ma.get(idx):
                        entry['annotations'] = ma[idx]
                    if pa.get(idx):
                        entry['parameterAnnotations'] = pa[idx]
                    yield entry


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    digest = hashlib.file_digest(args.apk.open('rb'), 'sha256').hexdigest()
    counts = {'declaredMethods': 0, 'interfaceMethods': 0, 'nativeMethods': 0, 'networkMethods': 0, 'dexEntries': []}
    network = []
    with zipfile.ZipFile(args.apk) as apk, gzip.open(args.output/'host-methods.jsonl.gz', 'wt', encoding='utf-8') as out:
        for name in sorted(n for n in apk.namelist() if n.endswith('.dex') and '/' not in n):
            counts['dexEntries'].append(name)
            for method in Dex(apk.read(name)).methods():
                method['sourceEntry'] = name
                out.write(json.dumps(method, ensure_ascii=False, separators=(',', ':'))+'\n')
                counts['declaredMethods'] += 1
                counts['interfaceMethods'] += method['interface']
                counts['nativeMethods'] += method['native']
                annotations = method.get('annotations', [])
                if any(any(x in a['type'] for x in ['/network/http/', 'Lretrofit2/http/', '/annotation/OperationType;', '/mtop/']) for a in annotations):
                    network.append(method)
    counts['networkMethods'] = len(network)
    meta = {'packageName': 'me.ele.crowdsource', 'versionName': '10.11.2', 'versionCode': 1247, 'sha256': digest,
            'scope': 'All declared DEX methods, including bundled libraries; network annotations are static evidence, not server coverage or runtime verification.', **counts}
    (args.output/'host-api-metadata.json').write_text(json.dumps(meta, ensure_ascii=False, indent=2)+'\n')
    (args.output/'host-api-catalog.json').write_text(json.dumps({'metadata': meta, 'methods': network}, ensure_ascii=False, separators=(',', ':'))+'\n')
    print(json.dumps(meta, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
