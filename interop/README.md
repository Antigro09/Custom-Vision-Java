# Native admitted-observation bridge test

This optional interoperability harness tests the released native NT stack, this
library's actual queue/decoder/session/clock, consume-once `Measurement`, and the
World-State adapter and tracker. It compiles fresh source into `build/interop`.
It is a desktop localhost test with synthetic timing evidence, not a controller,
camera, estimator or hardware qualification.

Supply an explicit compatible World-State checkout. There is no private task path
default, automatic clone or floating download. It must contain
`CustomVisionAdapter.adapt(VisionClient.Measurement, long nowUs)`.
The live normalization/tracking path uses its companion
`VisionTrackBridge(adapter, engine, originatingClient)` and
`acceptRobotNanoseconds(measurement, nowRobotNs)`.

After obtaining the pinned cached native dependencies as described in
[`docs/targets.md`](../docs/targets.md), run the two profiles sequentially:

```sh
python3 tools/check_native_world_state_bridge.py \
  --world-state-checkout "$WORLD_STATE_CHECKOUT" \
  --java-home2026 "$JDK17_HOME" \
  --classpath2026 build/python-nt-interop/classpath2026.txt \
  --native2026 build/adapters/native2026 \
  --java-home2027 "$JDK25_HOME" \
  --classpath2027 build/python-nt-interop/classpath2027.txt \
  --native2027 build/adapters/native2027
```

The classpath files are caller supplied records of locally resolved **pinned**
dependency JARs. The harness drops stale local class directories and recompiles
current CVJ and World-State source. It does not run Gradle or download anything.
Generate each file with the selected profile's `:wpilib2026:pythonInteropClasspath`
or `:wpilib2027:pythonInteropClasspath` task (the 2027 task uses `gradlew2027`).
An existing equivalent cached classpath record can also be passed explicitly.
Use `--profile 2026` or `--profile 2027` for one target. Ordinary library builds
do not require World-State or this harness.

The original object and empty-object golden bytes are hash checked. Watchdog
variants use the producer's complete cleared detection/target/selection shape,
preserving same-frame capture identity. The object's retained first delivery
stays pending; an advancing publication establishes the boot but the original
unverified correction remains rejected. Successful packets preserve its canonical
geometry and are explicitly modified to synthetic verified timing with current
NT server capture microseconds. The local NT-to-`System.nanoTime` relationship is
independently bracketed; Unix, Jetson monotonic and dequeue times do not replace
capture time. 2026 metadata is microseconds and alpha-7 metadata is nanoseconds;
JSON `_us` stays microseconds in both tests.
The pinned released artifacts exhibit microsecond wire resolution on this NT4
remote hop. Alpha-7 receives those values as nanoseconds (`wireUs * 1000`), so an
arbitrary publisher nanosecond value loses its sub-microsecond remainder.
Assertions verify the observed exact quantization and preserve the raw
**received** metadata into capture provenance. Both sender and receiver values
are emitted as `CVJ_WIRE_PRECISION` evidence alongside artifact hashes. This is
verified released-binary behavior, not a quote from an unavailable C++ source
inspection or a claim that arbitrary publisher nanosecond precision survives.
See the [pinned target/API evidence](../docs/targets.md) and
[previous Python-wire unit checks](../docs/PYTHON_INTEROP.md).
The client uses a bounded 50 ms reorder window and polls until capture-order
release. A zero window would close quiet-loop capture intervals before the
deliberately 10 ms old native observation arrives and correctly late-drop it.

Assertions cover exact original admission/clock/transport objects, World-State
normalization/projection/tracking, consume-once behavior, duplicate delivery,
same-frame invalidation of a queued observation and delayed revival tombstones,
first delivery of a caller-retained envelope after invalidation, recovery or boot
change (with an empty tracker, so rejection cannot be attributed to duplicate insertion),
missing synchronization, production no-argument drain expiry without a poll,
and shared NT instance ownership. These tests never assemble a successful
`SourceSession.Result`. Persistent world tracks survive source invalidation.

Each run emits bounded logs and evidence under `build/interop/world-state` with
exact source, dependency, fixture and native-library hashes. The native libraries
must already have been obtained and validated against the version pins. This
harness records hashes; it does not assert controller compatibility or replace
the separate artifact checksum verification step.
World-State's observed base commit and source-dirty flag are recorded; during
local feature development the actual source hashes are the tested dependency pin.
