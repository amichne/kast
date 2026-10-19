"""Closed subject admission, independent of the tool-producing checkout.

Generated fixtures enumerate every initial file, not a suffix-selected subset.
WorkspaceSnapshot remains the cleanup owner: its coordinator integration needs a
non-Git path enumerator for capture/examine/restore, including newly created files
and links. Do not replace it with a second fixture-specific restoration mechanism.
"""

from __future__ import annotations

import hashlib
import os
import stat
import subprocess
from enum import StrEnum
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, model_validator

REVISION = "5937ff1f46fbb0914c36226afb52426a4271c29c"


class SubjectRecord(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)


class InputFile(SubjectRecord):
    path: str = Field(min_length=1)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    mode: int = Field(ge=0, le=0o777)

    @model_validator(mode="after")
    def canonical_path(self) -> InputFile:
        path = Path(self.path)
        if (
            path.is_absolute()
            or path.as_posix() != self.path
            or any(part in (".", "..", "") for part in self.path.split("/"))
        ):
            raise ValueError("NONCANONICAL_INPUT_PATH")
        return self


class GitSubject(SubjectRecord):
    kind: Literal["git"] = "git"
    revision: str = Field(default=REVISION, pattern=r"^[0-9a-f]{40}$")


class GeneratedSubject(SubjectRecord):
    kind: Literal["generated"] = "generated"
    identity: str = Field(pattern=r"^[a-z0-9][a-z0-9.-]*$")
    workspace: str = Field(min_length=1)
    inputs: tuple[InputFile, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def complete_identity(self) -> GeneratedSubject:
        names = tuple(item.path for item in self.inputs)
        if names != tuple(sorted(set(names))):
            raise ValueError("INPUTS_NEED_UNIQUE_SORTED_PATHS")
        path = Path(self.workspace)
        if not path.is_absolute() or str(path) != self.workspace:
            raise ValueError("CANONICAL_ABSOLUTE_WORKSPACE_NEEDED")
        return self


type Subject = Annotated[GitSubject | GeneratedSubject, Field(discriminator="kind")]
SUBJECT: TypeAdapter[Subject] = TypeAdapter(Subject)


class AdmissionCode(StrEnum):
    IDENTITY_MISMATCH = "SUBJECT_IDENTITY_MISMATCH"
    REVISION_MISMATCH = "SOURCE_REVISION_MISMATCH"
    INPUT_MISSING = "SUBJECT_INPUT_MISSING"
    INPUT_ADDED = "SUBJECT_INPUT_ADDED"
    INPUT_ALTERED = "SUBJECT_INPUT_ALTERED"
    SYMLINK = "SUBJECT_SYMLINK_REJECTED"
    INPUT_UNREADABLE = "SUBJECT_INPUT_UNREADABLE"


class SubjectAdmissionError(ValueError):
    def __init__(self, code: AdmissionCode) -> None:
        self.code = code
        super().__init__(code.value)


def subject_digest(subject: Subject) -> str:
    return hashlib.sha256(SUBJECT.dump_json(subject)).hexdigest()


def admit_subject(root: Path, subject: Subject) -> None:
    if isinstance(subject, GitSubject):
        try:
            revision = subprocess.run(
                ["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True, check=True
            ).stdout.strip()
        except (OSError, subprocess.SubprocessError) as error:
            raise SubjectAdmissionError(AdmissionCode.REVISION_MISMATCH) from error
        if revision != subject.revision:
            raise SubjectAdmissionError(AdmissionCode.REVISION_MISMATCH)
        return
    if str(root.resolve()) != subject.workspace or str(root.absolute()) != subject.workspace:
        raise SubjectAdmissionError(AdmissionCode.IDENTITY_MISMATCH)
    try:
        if root.is_symlink():
            raise SubjectAdmissionError(AdmissionCode.SYMLINK)
        if not root.is_dir():
            raise SubjectAdmissionError(AdmissionCode.INPUT_MISSING)
        actual: set[str] = set()

        def failed(error: OSError) -> None:
            raise error

        for directory, directories, files in os.walk(root, followlinks=False, onerror=failed):
            for name in (*directories, *files):
                path = Path(directory) / name
                if path.is_symlink():
                    raise SubjectAdmissionError(AdmissionCode.SYMLINK)
            for name in files:
                path = Path(directory) / name
                if not path.is_file():
                    raise SubjectAdmissionError(AdmissionCode.INPUT_UNREADABLE)
                actual.add(path.relative_to(root).as_posix())
        expected = {item.path for item in subject.inputs}
        if expected - actual:
            raise SubjectAdmissionError(AdmissionCode.INPUT_MISSING)
        if actual - expected:
            raise SubjectAdmissionError(AdmissionCode.INPUT_ADDED)
        for item in subject.inputs:
            path = root / item.path
            if (
                hashlib.sha256(path.read_bytes()).hexdigest() != item.sha256
                or stat.S_IMODE(path.stat().st_mode) != item.mode
            ):
                raise SubjectAdmissionError(AdmissionCode.INPUT_ALTERED)
    except OSError as error:
        raise SubjectAdmissionError(AdmissionCode.INPUT_UNREADABLE) from error
