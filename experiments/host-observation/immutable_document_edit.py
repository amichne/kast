"""Owned disposable fixture edits for opt-in native qualification.

Preparation never launches or contacts an IDE. The caller explicitly runs the
returned ideScript in the already selected disposable host and admits its receipt.
Both forward and rollback requests require the exact current document preimage.
"""
import base64
from dataclasses import asdict, dataclass
from enum import Enum
from hashlib import sha256
import json
from pathlib import Path
import shutil
import uuid

from immutable_callback_oracle import SOURCE_FILES, SourceEdit
from static_callback_oracle import FixtureIntegrityError


MARKER = '.kast-owned-immutable-fixture'
MAX_SOURCE_BYTES = 65536


class EditMode(str, Enum):
    UNSAVED = 'UNSAVED'
    SAVED = 'SAVED'


class EditFailure(str, Enum):
    INPUT_REJECTED = 'INPUT_REJECTED'
    HOST_MISMATCH = 'HOST_MISMATCH'
    OWNERSHIP_REJECTED = 'OWNERSHIP_REJECTED'
    PROJECT_UNAVAILABLE = 'PROJECT_UNAVAILABLE'
    SOURCE_UNAVAILABLE = 'SOURCE_UNAVAILABLE'
    PREIMAGE_MISMATCH = 'PREIMAGE_MISMATCH'
    COMMIT_REJECTED = 'COMMIT_REJECTED'


@dataclass(frozen=True)
class OwnedFixture:
    root: Path
    token: str


@dataclass(frozen=True)
class DocumentEditRequest:
    root: str
    token: str
    file: str
    expectedHostPid: int
    before: str
    after: str
    mode: EditMode = EditMode.UNSAVED


@dataclass(frozen=True)
class DocumentEditReceipt:
    type: str
    hostPid: int
    file: str
    beforeSha256: str
    afterSha256: str
    savedSha256: str


@dataclass(frozen=True)
class DocumentEditRejected:
    type: str
    failure: EditFailure


@dataclass(frozen=True)
class PreparedEdit:
    request: DocumentEditRequest
    script: Path
    receipt: Path
    saved_sha256: str

    def rollback(self):
        return DocumentEditRequest(self.request.root, self.request.token, self.request.file,
                                   self.request.expectedHostPid, self.request.after, self.request.before, self.request.mode)


def digest(value):
    return sha256(value.encode('utf-8')).hexdigest()


def create_owned_fixture(parent: Path) -> OwnedFixture:
    """Create a new dedicated child; never claim ownership of an existing project."""
    root = parent.resolve(strict=True) / ('immutable-fixture-' + uuid.uuid4().hex)
    root.mkdir(mode=0o700)
    source = Path(__file__).parent / 'immutable-callback-fixture'
    for file in ('settings.gradle.kts', 'build.gradle.kts', *(module + '/build.gradle.kts' for module in ('suppliers', 'forwarding', 'invocation', 'independent')), *SOURCE_FILES):
        destination = root / file
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / file, destination)
    token = uuid.uuid4().hex
    (root / MARKER).write_text(token, encoding='utf-8')
    return OwnedFixture(root, token)


def owned_source(fixture: OwnedFixture, file: str) -> Path:
    root = fixture.root.resolve(strict=True)
    marker = root / MARKER
    if root != fixture.root or marker.is_symlink() or marker.read_text(encoding='utf-8') != fixture.token:
        raise FixtureIntegrityError('fixture ownership rejected')
    if file not in SOURCE_FILES:
        raise FixtureIntegrityError('source outside exact fixture inventory')
    target = root / file
    if target.resolve(strict=True) != target or not target.is_file():
        raise FixtureIntegrityError('fixture source path was redirected')
    return target


def prepare_edit(fixture: OwnedFixture, edit: SourceEdit, host_pid: int, directory: Path, mode=EditMode.UNSAVED) -> PreparedEdit:
    target = owned_source(fixture, edit.file)
    before = target.read_text(encoding='utf-8')
    if before.count(edit.before) != 1:
        raise FixtureIntegrityError('fixture edit preimage missing or ambiguous')
    request = DocumentEditRequest(str(fixture.root), fixture.token, edit.file, host_pid,
                                  before, before.replace(edit.before, edit.after, 1), mode)
    return prepare_request(fixture, request, directory)


def prepare_request(fixture: OwnedFixture, request: DocumentEditRequest, directory: Path) -> PreparedEdit:
    target = owned_source(fixture, request.file)
    if request.root != str(fixture.root) or request.token != fixture.token:
        raise FixtureIntegrityError('request belongs to another fixture')
    if not isinstance(request.mode, EditMode):
        raise FixtureIntegrityError('edit mode must be an explicit finite mode')
    if request.mode == EditMode.SAVED and target.read_text(encoding='utf-8') != request.before:
        raise FixtureIntegrityError('saved edit requires exact saved source preimage')
    if type(request.expectedHostPid) is not int or not 0 < request.expectedHostPid <= 9223372036854775807:
        raise FixtureIntegrityError('an exact disposable host PID is required')
    if request.before == request.after or any(len(value.encode('utf-8')) > MAX_SOURCE_BYTES for value in (request.before, request.after)):
        raise FixtureIntegrityError('document edit must be bounded and change its preimage')
    directory.mkdir(mode=0o700)
    input_path = directory.resolve() / 'input.json'
    input_path.write_text(json.dumps(asdict(request)), encoding='utf-8')
    template = Path(__file__).with_name('immutable-document-edit.kts.template').read_text(encoding='utf-8')
    script = directory.resolve() / 'edit.kts'
    script.write_text(template.replace('@INPUT_BASE64@', base64.b64encode(str(input_path).encode()).decode()), encoding='utf-8')
    return PreparedEdit(request, script, directory.resolve() / 'result.json', digest(target.read_text(encoding='utf-8')))


def admit_receipt(prepared: PreparedEdit) -> DocumentEditReceipt | DocumentEditRejected:
    if prepared.receipt.is_symlink() or prepared.receipt.stat().st_size > 4096:
        raise FixtureIntegrityError('edit receipt outside bounded ownership')
    def unique(pairs):
        output = {}
        for key, value in pairs:
            if key in output:
                raise FixtureIntegrityError('duplicate edit receipt field')
            output[key] = value
        return output
    value = json.loads(prepared.receipt.read_text(encoding='utf-8'), object_pairs_hook=unique)
    if isinstance(value, dict) and value.get('type') == 'REJECTED':
        if set(value) != set(DocumentEditRejected.__dataclass_fields__):
            raise FixtureIntegrityError('edit rejection shape rejected')
        try:
            return DocumentEditRejected('REJECTED', EditFailure(value['failure']))
        except ValueError as error:
            raise FixtureIntegrityError('edit rejection cause unknown') from error
    if not isinstance(value, dict) or set(value) != set(DocumentEditReceipt.__dataclass_fields__):
        raise FixtureIntegrityError('edit receipt shape rejected')
    receipt = DocumentEditReceipt(**value)
    request = prepared.request
    expected_saved = digest(request.after) if request.mode == EditMode.SAVED else prepared.saved_sha256
    if (receipt.type != 'APPLIED_' + request.mode.value or type(receipt.hostPid) is not int or
        receipt.hostPid != request.expectedHostPid or receipt.file != request.file or
        receipt.beforeSha256 != digest(request.before) or receipt.afterSha256 != digest(request.after) or
        receipt.savedSha256 != expected_saved):
        raise FixtureIntegrityError('native edit was not established by its exact receipt')
    return receipt
