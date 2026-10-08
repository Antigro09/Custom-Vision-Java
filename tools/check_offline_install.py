#!/usr/bin/env python3
"""Stage and compile a disposable Maven-coordinate consumer; never edit a robot project."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=("2026", "2027"), required=True)
    parser.add_argument("--java25", type=Path)
    args = parser.parse_args()
    version = "0.2.0-local.1"
    bundle = ROOT / "build/install-bundles" / f"customvision-{args.profile}-{version}"
    project = ROOT / "build/install-consumer" / args.profile
    if project.exists():
        shutil.rmtree(project)
    project.mkdir(parents=True)
    (project / "settings.gradle").write_text("rootProject.name = 'customvision-install-smoke'\n")
    java_version = 17 if args.profile == "2026" else 25
    wpilib_version = "2026.2.1" if args.profile == "2026" else "2027.0.0-alpha-7"
    build = """plugins { id 'java' }
java { toolchain { languageVersion = JavaLanguageVersion.of(JAVA_VERSION) } }
repositories {
    maven { url = 'https://frcmaven.wpi.edu/artifactory/api/download/release' }
    mavenCentral()
}
apply from: 'dependencies.gradle'
tasks.withType(JavaCompile).configureEach {
    options.release = JAVA_VERSION
    options.encoding = 'UTF-8'
    options.compilerArgs += ['-Xlint:all', '-Werror']
}
tasks.register('installedCoordinateCheck') {
    dependsOn compileJava
    def evidence = layout.buildDirectory.file('resolved-install.json')
    outputs.file evidence
    doLast {
        def artifacts = configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts
        def own = artifacts.findAll { it.moduleVersion.id.group == 'org.customvision' }
        def expected = ['customvision-protocol', 'customvision-api', 'customvision-controls', 'customvision-wpilibPROFILE'] as Set
        if ((own.collect { it.moduleVersion.id.name } as Set) != expected)
            throw new GradleException('installed own artifact graph does not match profile')
        if (own.any { it.moduleVersion.id.version != 'CANDIDATE_VERSION' })
            throw new GradleException('installed own artifact version differs from candidate')
        if (artifacts.any { (it.moduleVersion.id.group.startsWith('edu.wpi.first.') || it.moduleVersion.id.group.startsWith('org.wpilib.')) && it.moduleVersion.id.version != 'WPILIB_VERSION' })
            throw new GradleException('mixed WPILib dependency versions')
        if (own.any { !it.file.canonicalPath.startsWith(rootProject.file('customvision/maven').canonicalPath + File.separator) })
            throw new GradleException('own artifacts did not resolve from staged Maven repository')
        def receipt = [profile:'PROFILE', candidate:'CANDIDATE_VERSION', java:JAVA_VERSION, wpilib:'WPILIB_VERSION',
            ownCoordinates:own.collect { it.moduleVersion.id.toString() }.sort(),
            externalCoordinates:artifacts.findAll { it.moduleVersion.id.group != 'org.customvision' }.collect { it.moduleVersion.id.toString() }.sort(),
            compileOnly:true, hardware:false]
        def target = evidence.get().asFile
        target.parentFile.mkdirs()
        target.text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(receipt)) + '\\n'
    }
}
"""
    build = build.replace("JAVA_VERSION", str(java_version)).replace("CANDIDATE_VERSION", version)
    build = build.replace("WPILIB_VERSION", wpilib_version).replace("PROFILE", args.profile)
    (project / "build.gradle").write_text(build)
    shutil.copyfile(bundle / "example/dependencies.gradle", project / "dependencies.gradle")
    source = project / "src/main/java"
    source.mkdir(parents=True)
    shutil.copyfile(bundle / "example/NoMotionInstall.java", source / "NoMotionInstall.java")
    subprocess.run([sys.executable, str(bundle / "install.py"), "--project", str(project)], check=True)
    # Reuse checked upstream checksums. Own GAVs are verified by the manifest and
    # installer and then read only from this project's staged file repository.
    contents = (ROOT / "gradle/verification-metadata.xml").read_text()
    entry = '<trust group="org.customvision" reason="own staged candidate verified by bundle manifest"/>'
    if "<trusted-artifacts>" in contents:
        contents = contents.replace("<trusted-artifacts>", "<trusted-artifacts>" + entry, 1)
    else:
        contents = contents.replace("</configuration>", "<trusted-artifacts>" + entry + "</trusted-artifacts></configuration>", 1)
    (project / "gradle").mkdir()
    (project / "gradle/verification-metadata.xml").write_text(contents)
    wrapper = ROOT / ("gradlew" if args.profile == "2026" else "gradlew2027")
    home = ROOT / (".gradle-user" if args.profile == "2026" else ".gradle2027-user")
    command = [str(wrapper), "--no-daemon", "--offline", "--max-workers=1", "--gradle-user-home", str(home), "--project-dir", str(project)]
    if args.java25:
        command.append(f"-Dorg.gradle.java.installations.paths={args.java25.resolve()}")
    command.append("installedCoordinateCheck")
    subprocess.run(command, cwd=ROOT, check=True)
    receipt = json.loads((project / "build/resolved-install.json").read_text())
    print(f"Verified staged Maven {receipt['profile']} graph, {len(receipt['ownCoordinates'])} own coordinates, and no-motion compilation")


if __name__ == "__main__":
    main()
