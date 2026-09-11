"""Install the bounded test-only probe solely into an owned acceptance profile."""
import io
from pathlib import Path, PurePosixPath
import stat
import zipfile
from xml.etree import ElementTree

from acceptance_idea import digest
from hosted_change_acceptance import AcceptanceFailure, AcceptanceRejected


def stage_native_probe(archive: Path, root: Path, workspace: Path) -> str:
    if (not archive.is_absolute() or archive.is_symlink() or not archive.is_file()
            or root.resolve(strict=True) != root or root.stat().st_mode & 0o777 != 0o700
            or workspace != root / 'workspace'):
        raise AcceptanceRejected(AcceptanceFailure.INPUT)
    expected_digest = digest(archive)
    plugins = root / 'ide/plugins'
    destination = plugins / 'kast-native-fixture-probe'
    if destination.exists() or destination.is_symlink() or plugins.resolve(strict=True) != plugins:
        raise AcceptanceRejected(AcceptanceFailure.INPUT)
    try:
        with zipfile.ZipFile(archive) as bundle:
            members, names, descriptors = bundle.infolist(), set(), []
            if not members or len(members) > 64 or sum(item.file_size for item in members) > 4 * 1024 * 1024:
                raise AcceptanceRejected(AcceptanceFailure.INPUT)
            for item in members:
                path = PurePosixPath(item.filename)
                if (path.is_absolute() or '..' in path.parts or '\\' in item.filename or not path.parts
                        or path.parts[0] != destination.name or item.filename in names
                        or stat.S_ISLNK(item.external_attr >> 16)):
                    raise AcceptanceRejected(AcceptanceFailure.INPUT)
                names.add(item.filename)
                if item.filename.endswith('.jar'):
                    with zipfile.ZipFile(io.BytesIO(bundle.read(item))) as jar:
                        if 'META-INF/plugin.xml' in jar.namelist():
                            descriptors.append(ElementTree.fromstring(jar.read('META-INF/plugin.xml')))
            if len(descriptors) != 1 or descriptors[0].findtext('id') != 'io.github.amichne.kast.native-fixture-probe':
                raise AcceptanceRejected(AcceptanceFailure.INPUT)
            bundle.extractall(plugins)
    except (zipfile.BadZipFile, ElementTree.ParseError):
        raise AcceptanceRejected(AcceptanceFailure.INPUT) from None
    if digest(archive) != expected_digest:
        raise AcceptanceRejected(AcceptanceFailure.ARTIFACT_CHANGED)
    options = root / 'ide/idea.vmoptions'
    with options.open('a') as output:
        output.write(f'-Dkast.fixture.sandbox={root}\n-Dkast.fixture.project={workspace}\n')
    return expected_digest
