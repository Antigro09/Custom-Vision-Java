# Local controller package installation

The candidate version is `0.2.0-local.1`. There is no hosted Custom-Vision Maven
repository, verified online vendordep, or published release for this candidate.
These instructions install a checksum-verified local Maven directory. They do not
edit an actual robot project, start a robot program, or deploy anything.

Choose one target and keep its dependency set matched:

| Candidate profile | Controller target | Java | Exact WPILib |
| --- | --- | --- | --- |
| `2026` | roboRIO | 17 | `2026.2.1`, `edu.wpi.first.*` |
| `2027` | Systemcore | 25 | `2027.0.0-alpha-7`, `org.wpilib.*` |

The inspected official compatibility matrix provides no supported 2026/Systemcore
profile. The library is not a substitute for the team's matched image, Driver
Station, vendor libraries, robot clock evidence, and later hardware qualification.
Robot-code integration remains on hold.

## Build the candidate

Use an explicit checkout of this repository and locally installed JDKs. No sibling
task checkout is needed. `JAVA_HOME` selects the Java17 build runtime, and
`CVJ_JAVA25` selects a locally installed Java25 JDK for the separate profile:

```sh
./gradlew --no-daemon :protocol:check :api:check :controls:check :wpilib2026:check :wpilib2026:installExampleCheck
./gradlew2027 --no-daemon -Dorg.gradle.java.installations.paths="$CVJ_JAVA25" :protocol:check :api:check :controls:check :wpilib2027:check :wpilib2027:installExampleCheck
```

Run each command separately. Do not try the Java25 profile with the Gradle8.11
wrapper. The wrappers and third-party dependencies have exact pins, lockfiles and
checksum verification. Add `--offline` only after the corresponding wrapper and
dependencies have been cached; an uncached offline build is expected to fail.
Neither build silently downloads a JDK.

Each `installExampleCheck` builds that profile's local publications, makes its
offline package, and compiles `NoMotionInstall.java` against the unpacked package
jars plus the existing pinned official Java libraries. It does not use this
repository's class directories as a substitute for installed jars, initialize
HAL, bind NT sockets, call an estimator, or actuate hardware. Main-program unit
checks are explicit Gradle tasks; disabled JUnit discovery is not a test pass.

Outputs for each chosen year are:

```text
build/install-bundles/customvision-<year>-0.2.0-local.1/
build/install-bundles/customvision-<year>-0.2.0-local.1.zip
build/install-bundles/customvision-<year>-0.2.0-local.1.zip.sha256
```

Archive timestamps, order and permissions are fixed. `manifest.json` records each
included file's SHA256 and byte count, exact POM dependencies, Java level and
target. Repeating the same inputs must reproduce the archive. Source jars are
included for inspection; no general license grant is invented by this package.
See `THIRD_PARTY_NOTICES.md` for the inspected source/license status.
Informational `createdBy` labels are removed from packaged Gradle module metadata
so the common coordinates are not distinguished solely by the Gradle8/9 wrapper
used to make each profile. Resolver attributes, variants and dependency data are
retained; publication must still compare common file hashes before one canonical
upload.

The package contains only these own robot-facing artifacts, source jars, POMs and
Gradle module metadata:

| Artifact ID under `org.customvision` | Purpose |
| --- | --- |
| `customvision-protocol` | immutable decoding, lifecycle and capture provenance |
| `customvision-api` | camera construction and robot-loop observation/pose facade |
| `customvision-controls` | small injected motion-request contracts; caller owns execution |
| `customvision-wpilib2026` **or** `customvision-wpilib2027` | selected NT/geometry facade adapter |

The package includes an installer, install notes, notices and a small compile-only
example. It excludes WPILib/native binaries, tests/fixtures/reference tools,
Python/Jetson/model services, desktop GUIs/servers, datasets and weights. The robot's
matched WPILib project supplies official Java/native dependencies. World-State or
planner services are separate components; install their controller artifacts only
if the architecture actually runs them on the controller. A coprocessor service
does not become a robot artifact simply because it has a Java client.

## Verify Maven-coordinate resolution in a disposable project

After producing the packages, test each installation independently and sequentially:

```sh
python3 tools/check_offline_install.py --profile 2026
python3 tools/check_offline_install.py --profile 2027 --java25 "$CVJ_JAVA25"
```

This creates only `build/install-consumer/<year>`, verifies/stages the package,
resolves the four exact own Maven coordinates through its local file repository,
checks the pinned WPILib dependency graph and compiles the no-motion example with
`-Xlint:all -Werror`. Existing external dependency checksums are reused and the
commands run offline against the corresponding cache. The receipt at
`build/install-consumer/<year>/build/resolved-install.json` records actual resolved
coordinates. It is a plain Java compile check, not a generated team robot project,
GradleRIO deploy, native/hardware check or estimator integration.

## Inspect or stage into a caller-selected project

After unpacking the chosen archive, verify it before staging:

```sh
python3 /path/to/unpacked-bundle/install.py --verify-only
python3 /path/to/unpacked-bundle/install.py --project /path/to/explicit-wpilib-project
```

The explicit project must already exist and contain `build.gradle`. The installer
only copies the candidate's own `maven/` tree to the project-relative
`customvision/maven/` and writes an installation receipt under `customvision/`.
It refuses checksum changes, different existing files and mixing controller
profiles. It leaves `build.gradle`, source files, vendor configuration and deploy
settings alone. Do not run this step on a team robot repository while the current
integration hold applies; a disposable local Gradle project is sufficient to test
staging.

The bundled `example/dependencies.gradle` is an explicit snippet for later review:

```groovy
repositories {
    maven { url = rootProject.file('customvision/maven').toURI() }
}
dependencies {
    implementation 'org.customvision:customvision-wpilib2026:0.2.0-local.1'
    implementation 'org.customvision:customvision-controls:0.2.0-local.1'
}
```

Use `customvision-wpilib2027` only in the matched Java25/alpha-7 project. Adapter
POMs declare their exact WPILib and own common dependencies. Gradle conflict
resolution can otherwise select a different version already requested by an
application; inspect the application's resolved graph and reject mixed WPILib
years or unsupported upgrades before integration. The local repository holds
only own artifacts; it cannot make an uncached application's third-party
resolution offline by itself. No dependencies are shaded into controller jars.

`NoMotionInstall.java` requires the caller's shared `NetworkTableInstance`, verified
robot monotonic nanosecond clock and independently checked NT-to-robot epoch
evidence. The helper owns only its created subscribers, never the supplied shared
instance. Its periodic call runs on one robot-loop owner. Consume-once observations
retain actual source admission and full clock outcomes for World-State; reading
status or pose snapshots does not create a second measurement. A pose policy needs
explicit quality gates and a calibration/noise callback before pose estimates are
offered for robot-owned fusion. Missing evidence is a visible rejection, not a
guessed safe default. Single-tag PnP and MultiTag PnP remain distinct producer
methods; no heading-assisted solver or Limelight MT1/MT2 equivalence is supplied.

## Online vendordep and release audit

The local Maven coordinates and POM layout are prepared, but hosted installation
has not been executed. Current work is local-only and authorizes no release,
hosting or push. A future public vendordep requires all of the following concrete
steps after explicit publication scope:

1. Choose an actual hosting destination controlled by the owner. Upload only the
   selected candidate's own Maven paths/POMs/module metadata/source jars plus checksums, preserve
   immutable coordinates and independently GET every uploaded artifact to match
   the local manifest. Do not substitute repository source URLs for Maven URLs.
2. Inspect the exact GradleRIO/vendor-dependency schema for the chosen WPILib
   version. Prepare separate 2026 and 2027 JSON files with stable identities,
   exact Java coordinates and the actual reachable Maven URL; validate required
   fields and year/version behavior with those toolchains. The vendordep parser
   schema has not been verified in this local packaging task, so no plausible JSON
   is presented as an installable vendordep. This library contributes no native
   artifacts and must not duplicate GradleRIO's WPILib native dependencies.
3. Publish the real JSON at an owner-selected reachable URL only after that schema
   check. In clean disposable matched WPILib projects, install through the normal
   vendor flow, verify all resolved coordinates, compile the provided example,
   and record whether cache/network was used. These are desktop package checks;
   they do not authorize robot deployment or establish controller performance.
4. If a release is authorized, attach the verified year-specific archives and
   checksum/manifest records to the selected version tag and verify the release
   assets' remote bytes independently. Neither a tag nor a release has been
   created for this candidate.

Until those steps are complete, the supported installation proof is the offline
Maven directory and unpacked-library compile check. Existing public source `main`
is preserved; this feature candidate is local and is not a remotely published
Maven artifact.
