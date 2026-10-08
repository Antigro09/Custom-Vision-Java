#!/usr/bin/env python3
"""Reproduce legacy bytes through the exact pinned Publisher with injected CPU/NT clocks."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import types

ROOT = Path(__file__).resolve().parents[1]
PIN = '2aee0fc1794b31539b02000d16791d4eec8df29a'
PUBLISHER_SHA = '0b156bd4929d00183593d46cbe66dbc70d962622c77ec4a6a4e68992b05e0565'
source = ROOT / 'reference/legacy-publisher.py'
assert hashlib.sha256(source.read_bytes()).hexdigest() == PUBLISHER_SHA
spec = importlib.util.spec_from_file_location('legacy_pinned_publisher', source)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
class Topic:
    def __init__(self): self.value = None
    def publish(self, options): return self
    def set(self, value): self.value = value
class Table:
    def __getattr__(self, name): return lambda key: Topic()
class Instance:
    def getServerTimeOffset(self): return 2_000_000
    def getTable(self, name): return Table()
    def flush(self): pass
nt = types.SimpleNamespace(_now=lambda: 1_234_567_920_123, PubSubOptions=lambda **kw: kw)
sys.modules['ntcore'] = nt
result = []
for name in ['single_tag', 'same_frame_watchdog', 'objects_compact_covariance_selection', 'empty_objects', 'poi_camera_only']:
    input_path = ROOT / 'fixtures/fixtures' / (name + '.json')
    payload = json.loads(input_path.read_bytes())
    for key in ['protocol_profile', 'packet_seq', 'calibration_revision', 'mount_revision', 'field_layout_revision', 'timing']:
        payload.pop(key, None)
    old_clock = module.time.monotonic_ns
    module.time.monotonic_ns = lambda: (payload['capture_monotonic_us'] + 30_000) * 1000
    publisher = module.Publisher({'enabled': False, 'table': '/LegacyFixture'})
    publisher.instance = Instance()
    try:
        publisher.publish(payload)
    finally:
        module.time.monotonic_ns = old_clock
    encoded = publisher.tables[payload['pipeline']]['result'].value.encode('utf-8')
    relative = 'legacy/' + name + '.json'
    output = ROOT / 'fixtures' / relative
    if '--check' in sys.argv:
        assert output.read_bytes() == encoded, relative + ' differs from actual baseline serializer'
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(encoded)
    result.append({'name': name, 'path': relative, 'sha256': hashlib.sha256(encoded).hexdigest(),
                   'byte_count': len(encoded), 'input_additive_sha256': hashlib.sha256(input_path.read_bytes()).hexdigest()})
manifest = {'manifest_version': 1, 'profile': 'legacy-schema2', 'producer_revision': PIN,
    'publisher_sha256': PUBLISHER_SHA, 'hardware': False,
    'provenance': 'Actual pinned baseline Publisher.publish -> wire_packet -> json.dumps with stub NT topics and integer microsecond clocks; geometry inputs derived from producer-owned synthetic goldens, additive keys removed before legacy publication.',
    'fixtures': result}
manifest_bytes = (json.dumps(manifest, indent=2) + '\n').encode()
path = ROOT / 'fixtures/legacy-manifest.json'
if '--check' in sys.argv: assert path.read_bytes() == manifest_bytes
else: path.write_bytes(manifest_bytes)
print(f'Legacy pinned Publisher interoperability PASS: {len(result)} exact strings')
