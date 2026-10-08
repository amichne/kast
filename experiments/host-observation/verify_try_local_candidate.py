"""Admit source-stable candidate bytes and an isolated loaded native composition.

The observed build receipt retains its existing original type. This wrapper
records a candidate build, never callback acceptance or completed Pi semantics.
"""

from dataclasses import asdict, dataclass
from hashlib import sha256
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile
import argparse
import json

import qualify_callback_tracing as build
import reproduce_semantic_queries as native


@dataclass(frozen=True)
class TryLocalBuild:
    observedBuild: build.CandidateBuildReceipt
    qualificationSlice: str
    publicContractVersion: int
    type: str = 'TRY_LOCAL_CANDIDATE_BUILD'
    qualification: str = 'NATIVE_AND_PI_QUALIFICATION_PENDING'


@dataclass(frozen=True)
class ArtifactAdmission:
    sourceRevision: str
    sourcePatchSha256: str
    candidatePluginSha256: str
    pinnedRoot: str
    hostPid: int
    processStart: str
    requiredOwners: tuple[str, ...]
    qualificationSlice: str
    publicContractVersion: int
    type: str = 'ISOLATED_TRY_LOCAL_NATIVE_ARTIFACTS_ADMITTED'
    qualification: str = 'PI_REGISTERED_TOOL_AND_SEMANTIC_ASSERTIONS_PENDING'


class AdmissionFailure(AssertionError):
    pass


def require(condition, reason):
    if not condition:
        raise AdmissionFailure(reason)


def read_json(path, limit=32 * 1024 * 1024):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= limit, 'BOUNDED_REGULAR_RECEIPT_REQUIRED')
    return json.loads(path.read_text())


def admit(args):
    slice_ = native.QualificationSlice.admit(args.qualification_slice)
    wrapper = read_json(args.build_receipt)
    require(set(wrapper) == {'type', 'qualification', 'observedBuild', 'qualificationSlice', 'publicContractVersion'} and wrapper['type'] == 'TRY_LOCAL_CANDIDATE_BUILD'
            and wrapper['qualification'] == 'NATIVE_AND_PI_QUALIFICATION_PENDING', 'BUILD_WRAPPER_REJECTED')
    require(wrapper['qualificationSlice'] == slice_.value, 'BUILD_SLICE_MISMATCH')
    native.admit_public_contract_slice(slice_, wrapper['publicContractVersion'])
    receipt = wrapper['observedBuild']
    require(set(receipt) == set(build.CandidateBuildReceipt.__dataclass_fields__), 'BUILD_RECEIPT_FIELDS_REJECTED')
    require(receipt['type'] == 'STATIC_CALLBACK_CANDIDATE_BUILD', 'OBSERVED_BUILD_TYPE_REJECTED')
    source = Path(receipt['sourceTree']).resolve(strict=True)
    fingerprint = asdict(build.source_fingerprint(source))
    require(receipt['sourceBefore'] == receipt['sourceAfter'] == fingerprint, 'SOURCE_CHANGED_AFTER_BUILD')
    process = receipt['process']
    require(process.get('outcome') == 'completed' and process.get('exitCode') == 0
            and Path(process['cwd']).resolve() == source
            and {':runtime:hosted:hostedPlugin', ':cli:installDist'} <= set(process['command']), 'SUCCESSFUL_EXACT_BUILD_REQUIRED')
    candidate = args.candidate.resolve(strict=True)
    plugin = args.plugin.resolve(strict=True)
    require(native.inventory(candidate) == receipt['candidateCliHashes'], 'CANDIDATE_CLI_CHANGED')
    require(native.digest(plugin) == receipt['candidatePluginSha256'], 'CANDIDATE_PLUGIN_CHANGED')
    require(asdict(build.candidate_composition_receipt(source, candidate)) == receipt['composition'], 'CANONICAL_MARKER_CHANGED')
    pinned = read_json(args.pin)
    require(pinned['qualificationSlice'] == slice_.value, 'PIN_SLICE_MISMATCH')
    native.admit_public_contract_slice(slice_, pinned['publicContractVersion'])
    require({key: pinned['source'][key] for key in fingerprint} == fingerprint, 'NATIVE_SOURCE_PIN_CHANGED')
    require(pinned['cli']['executable'] == str(candidate / 'bin/kast-tool-rpc')
            and pinned['cli']['transport'] == 'TOOL_RPC'
            and pinned['cli']['sha256'] == native.digest(candidate / 'bin/kast-tool-rpc'), 'NATIVE_CLI_PIN_CHANGED')
    cli_jars = {path.name: native.digest(path) for path in (candidate / 'lib').glob('*.jar')}
    require(cli_jars and cli_jars == {Path(path).name: value for path, value in pinned['cli']['jars'].items()}, 'NATIVE_CLI_JARS_CHANGED')
    owners = pinned['plugin']['native']['classResources']
    native.admit_native_owner_profile(slice_, owners)
    plugin_jars, classes = {}, {}
    require(plugin.stat().st_size <= 1024 * 1024 * 1024, 'PLUGIN_ARCHIVE_BOUND_EXCEEDED')
    with ZipFile(plugin) as archive:
        jar_names = [name for name in archive.namelist() if '/lib/' in name and name.endswith('.jar')]
        require(0 < len(jar_names) <= 256, 'PLUGIN_JAR_COUNT_REJECTED')
        for name in jar_names:
            data = archive.read(name)
            require(Path(name).name not in plugin_jars, 'PLUGIN_JAR_NAME_AMBIGUOUS')
            plugin_jars[Path(name).name] = sha256(data).hexdigest()
            with ZipFile(BytesIO(data)) as jar:
                for owner in owners:
                    resource = owner.replace('.', '/') + '.class'
                    if resource not in jar.namelist():
                        continue
                    require(owner not in classes, 'PLUGIN_CLASS_AMBIGUOUS')
                    classes[owner] = sha256(jar.read(resource)).hexdigest()
    require(plugin_jars == {Path(path).name: value for path, value in pinned['plugin']['jars'].items()}, 'LOADED_PLUGIN_JARS_DIFFER')
    require(classes == {owner: observed['sha256'] for owner, observed in owners.items()}, 'LOADED_CLASS_BYTES_DIFFER')
    owned = args.owned_root.resolve(strict=True)
    root = args.root.resolve(strict=True)
    require(root in (owned / 'fixture', owned / 'konditional-copy'), 'UNOWNED_PROJECT_REJECTED')
    require(pinned['fixture']['root'] == str(root) and pinned['fixture']['hashes'] == native.inventory(root), 'FIXTURE_PIN_CHANGED')
    environment = read_json(owned / 'environment.json')
    host, profile = pinned['host'], pinned['profile']
    require(environment['root'] == str(owned) and environment['type'] == 'OWNED_TRY_LOCAL_NATIVE_ENVIRONMENT', 'OWNED_ENVIRONMENT_REJECTED')
    require(host['pid'] != environment['protectedPid'] and profile['hostPid'] == host['pid']
            and profile['processStart'] == host['processStart'] and profile['type'] == 'NATIVE_PROFILE_PATHS', 'OWNED_HOST_IDENTITY_REJECTED')
    expected_profile = {'ideaHome': owned / 'IntelliJ IDEA.app/Contents', 'userHome': owned / 'h',
                        'config': owned / 'config', 'system': owned / 'system', 'plugins': owned / 'plugins'}
    require(all(profile[key] == str(path) for key, path in expected_profile.items()), 'NATIVE_PROFILE_PATHS_REJECTED')
    require(Path(pinned['plugin']['native']['path']).resolve().is_relative_to(owned / 'plugins'), 'UNOWNED_LOADED_PLUGIN_REJECTED')
    require(host['model']['root'] == str(root) and host['model']['smart'] and host['model']['savedDocuments']
            and host['model']['committedPsi'] and host['model']['gradleModelState'] == 'captured', 'NATIVE_IMPORTED_MODEL_UNCONFIRMED')
    if args.end_pin is not None:
        ending = read_json(args.end_pin)
        for key in ('qualificationSlice', 'publicContractVersion', 'cli', 'plugin', 'fixture', 'source', 'profile', 'limits'):
            require(ending[key] == pinned[key], 'END_PIN_CHANGED_' + key.upper())
        for key in ('ideaBuild', 'jbr', 'kotlinPlugin', 'javaHome', 'pid', 'processStart', 'model'):
            require(ending['host'][key] == host[key], 'END_HOST_CHANGED_' + key.upper())
    return ArtifactAdmission(fingerprint['commit'], fingerprint['patchSha256'], receipt['candidatePluginSha256'],
                             str(root), host['pid'], host['processStart'], tuple(sorted(slice_.changed_owners)),
                             slice_.value, slice_.public_contract_version)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    record = commands.add_parser('build', help='Wrap an actual source-stable successful build observation')
    for name in ('source', 'before', 'process', 'candidate', 'plugin'):
        record.add_argument('--' + name, type=Path, required=True)
    verify = commands.add_parser('admit', help='Match candidate bytes to actual isolated native pins')
    for name in ('build-receipt', 'candidate', 'plugin', 'pin', 'root', 'owned-root'):
        verify.add_argument('--' + name, type=Path, required=True)
    verify.add_argument('--end-pin', type=Path)
    for command in (record, verify):
        command.add_argument('--qualification-slice', choices=tuple(value.value for value in native.QualificationSlice), required=True)
    args = parser.parse_args()
    if args.command == 'build':
        before = build.SourceFingerprint(**read_json(args.before))
        observed = build.candidate_build_receipt(args.source.resolve(strict=True), before, read_json(args.process),
                                                 args.candidate.resolve(strict=True), args.plugin.resolve(strict=True))
        slice_ = native.QualificationSlice.admit(args.qualification_slice)
        result = TryLocalBuild(observed, slice_.value, slice_.public_contract_version)
    else:
        result = admit(args)
    print(json.dumps(asdict(result), indent=2))


if __name__ == '__main__':
    main()
