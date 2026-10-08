#!/usr/bin/env python3
"""Verify exported common source pins; optionally also verify locally built artifacts."""
import hashlib
import json
from pathlib import Path
import sys
root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / 'docs/api-manifest.json').read_text())
entries = list(manifest['files'])
if '--artifacts' in sys.argv:
    entries += manifest['artifacts']
for entry in entries:
    path = root / entry['path']
    data = path.read_bytes()
    assert len(data) == entry['bytes'], entry['path'] + ' size mismatch'
    assert hashlib.sha256(data).hexdigest() == entry['sha256'], entry['path'] + ' SHA256 mismatch'
print(f'API manifest PASS: {len(entries)} exact source/artifact pins')
