#!/usr/bin/env python3
"""Extract literal API references from Mist archives without executing the APK."""
import argparse
import hashlib
import io
import json
import re
import tarfile
import zipfile
from pathlib import Path


def extract(apk_path):
    references, errors = [], []
    packs = members = 0
    pattern = re.compile(r"[\"'](urlPath|api|apiName|operationType)[\"']\s*:\s*[\"']([^\"']+)[\"']")
    with zipfile.ZipFile(apk_path) as apk:
        for name in apk.namelist():
            if not name.endswith('.mistp'):
                continue
            try:
                with tarfile.open(fileobj=io.BytesIO(apk.read(name)), mode='r:gz') as archive:
                    packs += 1
                    for member in archive:
                        if not member.isfile() or not member.name.endswith(('.mist', '.json', '.js')):
                            continue
                        content = archive.extractfile(member).read().decode('utf-8', errors='replace')
                        members += 1
                        for hit in pattern.finditer(content):
                            value = hit.group(2)
                            if value.startswith('$:'):
                                continue
                            references.append(dict(asset=name, member=member.name, key=hit.group(1),
                                value=value, context=content[max(0, hit.start()-100):hit.end()+260]))
            except (tarfile.TarError, OSError, ValueError) as error:
                errors.append(dict(asset=name, error=str(error)))
    with Path(apk_path).open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    return dict(apk='蜂鸟众包 10.11.2', packageName='me.ele.crowdsource', sha256=digest,
        scope='Literal endpoint references in bundled gzip tar Mist packages; expressions and remotely delivered bundles are not complete',
        packs=packs, members=members, references=references, errors=errors)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    result = extract(args.apk)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + chr(10), encoding='utf-8')
    print(json.dumps(dict(packs=result['packs'], members=result['members'],
        references=len(result['references']), uniqueValues=len({x['value'] for x in result['references']}),
        errors=len(result['errors'])), ensure_ascii=False))
    if result['errors']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
