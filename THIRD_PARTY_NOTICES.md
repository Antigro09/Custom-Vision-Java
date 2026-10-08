# Third-party notices and source attribution

## Custom-Vision producer contract and legacy reference

`fixtures/` mirrors the producer-owned schemas, normative field documentation,
physical checklist and exact synthetic packet corpus from
[Antigro09/Custom-Vision](https://github.com/Antigro09/Custom-Vision/tree/e9efef9e534f3841acacf782131c927485b7b4f2/protocol).
The producer core revision is `61b2e636b11d4556097ee586d594e086d9d69dc4`;
`fixtures/fixture-manifest.json` retains the producer's source and packet hashes.
`fixtures/README.md` is adapted to identify the consumer mirror and producer-only
generation instructions. Packet bytes, schemas and normative fields are unchanged.

`reference/legacy-publisher.py` and `reference/legacy-networktables.md` are copied
from the same owner's repository at
[`2aee0fc1794b31539b02000d16791d4eec8df29a`](https://github.com/Antigro09/Custom-Vision/tree/2aee0fc1794b31539b02000d16791d4eec8df29a).
The copied publisher is unmodified; its SHA256 is
`0b156bd4929d00183593d46cbe66dbc70d962622c77ec4a6a4e68992b05e0565`.
Five legacy fixtures are generated from that publisher with synthetic inputs and
injected clocks, as recorded in `fixtures/legacy-manifest.json`.

The inspected Custom-Vision revisions contain no root license for these files.
This attribution does not invent a license grant or relicense the producer source.
The component is published by the repository owner. No general license grant for
the newly authored component is declared by this publication.

## Gradle wrappers

`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` and the separately
configured `gradle2027/wrapper/gradle-wrapper.jar` derive from Gradle's official
wrapper distribution. Wrapper scripts retain their upstream copyright and
Apache License 2.0 headers; the JARs retain `META-INF/LICENSE`.
The bundled [license](licenses/gradle/Apache-2.0.txt) is extracted verbatim from
the checked wrapper JAR. The official distribution
[notice](licenses/gradle/NOTICE.txt) is also retained. `gradlew2027` is a small local
launcher for the separately pinned wrapper configuration.

Official Gradle distributions are downloaded with pinned SHA256 values by their
wrappers. WPILib and other Java/native dependencies are downloaded separately with
exact pins and checksum verification; their binaries and full distributions are
not committed here. Their upstream licenses continue to apply. The local library
JARs described in `docs/api-manifest.json` are build outputs, not published Maven
artifacts or vendordeps.
