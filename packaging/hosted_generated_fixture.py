"""Actual Gradle source generation and provenance inputs for the private native fixture.

The runner executes the returned Gradle task before IDE import. Only the imported
Gradle/JPS model can establish generated provenance; this helper never writes IDE
metadata or claims that filesystem observation alone establishes that provenance.
"""
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import stat

from hosted_read_fixture import ReadFixture, ReadFixtureRejected, _digest


SOURCE_DIRECTORY = 'native-fixture-sources'
MOVEMENT_DIRECTORY = SOURCE_DIRECTORY + '/movement'
GENERATED_DIRECTORY = SOURCE_DIRECTORY + '/generated'
MOVEMENT_FILE = MOVEMENT_DIRECTORY + '/NativeModelMovementTarget.kt'
GENERATED_FILE = GENERATED_DIRECTORY + '/NativeGeneratedTarget.kt'
MOVEMENT_SOURCE = 'package fixture\n\nclass NativeModelMovementTarget\n'
GENERATED_SOURCE = 'package fixture\n\nclass NativeGeneratedTarget\n'
GENERATE_TASK = 'generateNativeAcceptanceSource'

# A source directory is registered with Kotlin and the Gradle IDEA model. The
# generated output is written by Gradle, with an explicit compile dependency.
# The directory name itself is not used as evidence of generated provenance.
INITIAL_GRADLE_CONFIGURATION = '''
apply(plugin = "idea")
val nativeMovementRoot = layout.projectDirectory.dir("native-fixture-sources/movement")
val nativeGeneratedRoot = layout.projectDirectory.dir("native-fixture-sources/generated")
val nativeGeneratedFile = nativeGeneratedRoot.file("NativeGeneratedTarget.kt")
val generateNativeAcceptanceSource = tasks.register("generateNativeAcceptanceSource") {
    outputs.file(nativeGeneratedFile)
    doLast {
        val output = nativeGeneratedFile.asFile
        output.parentFile.mkdirs()
        output.writeText("package fixture\\n\\nclass NativeGeneratedTarget\\n")
    }
}
kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(nativeMovementRoot)
        kotlin.srcDir(nativeGeneratedRoot)
    }
}
tasks.named("compileKotlin") { dependsOn(generateNativeAcceptanceSource) }
extensions.configure<org.gradle.plugins.ide.idea.model.IdeaModel> {
    module { generatedSourceDirs.add(nativeGeneratedRoot.asFile) }
}
'''

MODEL_AMENDMENT = '''
// Native acceptance: change the Gradle provenance of this dedicated source root.
extensions.configure<org.gradle.plugins.ide.idea.model.IdeaModel> {
    module { generatedSourceDirs.add(nativeMovementRoot.asFile) }
}
'''


@dataclass(frozen=True)
class PreparedGeneratedFixture:
    original: ReadFixture
    initial_build: str
    gradle_tasks: tuple[str, ...] = (GENERATE_TASK,)


@dataclass(frozen=True)
class GeneratedFixture:
    read_fixture: ReadFixture
    initial_build: str

    @property
    def amended_build(self):
        return self.initial_build + MODEL_AMENDMENT

    def evidence(self):
        return {'generationTask': GENERATE_TASK, 'generatorOutputSha256': _sha(GENERATED_SOURCE),
                'movementSourceSha256': _sha(MOVEMENT_SOURCE),
                'initialBuildSha256': _sha(self.initial_build),
                'amendedBuildSha256': _sha(self.amended_build),
                'provenanceAuthority': 'pending-native-gradle-and-jps-model-observation'}


def _sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


def _owned(workspace, relative):
    path = workspace / relative
    if not path.is_relative_to(workspace) or workspace.resolve() != workspace:
        raise ReadFixtureRejected('GENERATED_FIXTURE_OWNERSHIP_REJECTED')
    for ancestor in (path, *path.parents):
        if ancestor == workspace:
            break
        try:
            info = ancestor.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or info.st_uid != os.getuid():
            raise ReadFixtureRejected('GENERATED_FIXTURE_OWNERSHIP_REJECTED')
    return path


def prepare_generated_fixture(read_fixture: ReadFixture) -> PreparedGeneratedFixture:
    """Append Gradle inputs after prepare_read_fixture; do not synthesize its output."""
    workspace = read_fixture.workspace
    base = _owned(workspace, SOURCE_DIRECTORY)
    if base.exists() or base.is_symlink() or not read_fixture.unchanged():
        raise ReadFixtureRejected('GENERATED_FIXTURE_PREIMAGE_REJECTED')
    build = _owned(workspace, 'build.gradle.kts')
    _digest(build)
    initial = build.read_text() + INITIAL_GRADLE_CONFIGURATION
    movement = _owned(workspace, MOVEMENT_FILE)
    movement.parent.mkdir(mode=0o700, parents=True)
    movement.write_text(MOVEMENT_SOURCE)
    build.write_text(initial)
    return PreparedGeneratedFixture(read_fixture, initial)


def finalize_generated_fixture(prepared: PreparedGeneratedFixture) -> GeneratedFixture:
    """After the runner's real Gradle task succeeds, admit output and freeze the inventory."""
    original, initial = prepared.original, prepared.initial_build
    workspace = original.workspace
    if _digest(_owned(workspace, 'build.gradle.kts')) != _sha(initial):
        raise ReadFixtureRejected('GENERATED_FIXTURE_BUILD_REJECTED')
    for name, expected in original.files:
        if name != 'build.gradle.kts' and _digest(_owned(workspace, name)) != expected:
            raise ReadFixtureRejected('GENERATED_FIXTURE_PREIMAGE_REJECTED')
    for name, source in ((MOVEMENT_FILE, MOVEMENT_SOURCE), (GENERATED_FILE, GENERATED_SOURCE)):
        if _digest(_owned(workspace, name)) != _sha(source):
            raise ReadFixtureRejected('GENERATED_FIXTURE_OUTPUT_REJECTED')
    files = dict(original.files)
    files.update({'build.gradle.kts': _sha(initial), MOVEMENT_FILE: _sha(MOVEMENT_SOURCE),
                  GENERATED_FILE: _sha(GENERATED_SOURCE)})
    inventory = ReadFixture(workspace, tuple(sorted(files.items())), original.oracle)
    return GeneratedFixture(inventory, initial)


def amend_generated_provenance(fixture: GeneratedFixture):
    """Change one exact Gradle build preimage; native reimport remains a separate effect."""
    workspace = fixture.read_fixture.workspace
    build = _owned(workspace, 'build.gradle.kts')
    if _digest(build) != _sha(fixture.initial_build):
        raise ReadFixtureRejected('GENERATED_FIXTURE_BUILD_REJECTED')
    for name, source in ((MOVEMENT_FILE, MOVEMENT_SOURCE), (GENERATED_FILE, GENERATED_SOURCE)):
        if _digest(_owned(workspace, name)) != _sha(source):
            raise ReadFixtureRejected('GENERATED_FIXTURE_OUTPUT_REJECTED')
    build.write_text(fixture.amended_build)
    return {'outcome': 'gradle-provenance-amended', 'beforeSha256': _sha(fixture.initial_build),
            'afterSha256': _sha(fixture.amended_build), 'sourceBytesUnchanged': True,
            'nativeReimport': 'required'}
