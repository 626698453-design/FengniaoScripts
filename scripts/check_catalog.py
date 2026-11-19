#!/usr/bin/env python3
"""Validate bundled signature counts and independent reverse evidence (no network)."""
import argparse
import collections
import gzip
import json
from pathlib import Path


def check(root):
    metadata = json.loads((root / 'host-api-metadata.json').read_text())
    catalog = json.loads((root / 'host-api-catalog.json').read_text())
    independent = json.loads((root / 'dexclub-network-evidence.json').read_text())
    profile = json.loads((root / 'host-profile.json').read_text())
    mist = json.loads((root / 'mist-endpoint-references.json').read_text())
    assert metadata == catalog['metadata']
    assert metadata['sha256'] == profile['target']['sha256'] == mist['sha256']
    network = {row['descriptor'] for row in catalog['methods']}
    assert len(network) == len(catalog['methods']) == metadata['networkMethods'] == 338
    assert network == {row['descriptor'] for row in independent['methods']}
    required = {profile['settings']['onCreate'], profile['sendPizza']['entry'],
        profile['serviceProxy']['singleton'], profile['serviceProxy']['factory'],
        profile['serviceProxy']['nativeProxyFactory'],
        'Lxc0/b;->execute()Landroid/util/Pair;',
        'Lcom/alibaba/fastjson/JSON;->parseObject(Ljava/lang/String;)Lcom/alibaba/fastjson/JSONObject;'}
    counts = collections.Counter()
    sources = set()
    with gzip.open(root / 'host-methods.jsonl.gz', 'rt', encoding='utf-8') as source:
        for line in source:
            row = json.loads(line)
            descriptor = row['owner'] + '->' + row['name'] + '(' + ''.join(row['parameters']) + ')' + row['returnType']
            assert descriptor == row['descriptor']
            required.discard(descriptor)
            counts['declaredMethods'] += 1
            counts['interfaceMethods'] += row['interface']
            counts['nativeMethods'] += row['native']
            sources.add(row['sourceEntry'])
    assert not required, required
    assert all(counts[key] == metadata[key] for key in counts), counts
    assert sources == set(metadata['dexEntries'])
    assert not mist['errors']
    assert (mist['packs'], mist['members'], len(mist['references']),
        len({row['value'] for row in mist['references']})) == (206, 1880, 693, 253)
    print(json.dumps(dict(status='PASS', **counts, networkMethods=len(network),
        parameterAnnotatedNetworkMethods=sum(bool(row.get('parameterAnnotations')) for row in catalog['methods']),
        mistReferences=len(mist['references']), sha256=metadata['sha256']), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('assets', type=Path)
    check(parser.parse_args().assets)
