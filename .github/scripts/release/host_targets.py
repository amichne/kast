"""Strict compile-target identities shared by developer records and catalogs."""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import StrEnum
from typing import TypeGuard

import developer_catalog as catalog

LINES = ("262", "263")


class Failure(StrEnum):
    INVALID_TARGETS = "INVALID_TARGETS"
    INVALID_PROFILE = "INVALID_PROFILE"


@dataclass(frozen=True, slots=True)
class SdkTarget:
    intellijReleaseLine: str
    ideBuild: str
    kotlinPluginBuild: str


@dataclass(frozen=True, slots=True)
class HostTarget(SdkTarget):
    record: catalog.RecordReference


@dataclass(frozen=True, slots=True)
class Profile:
    schemaVersion: int
    defaultTarget: str
    targets: tuple[SdkTarget, ...]


def record_name(version: str, line: str) -> str:
    suffix = "" if line == "262" else f"-idea-{line}"
    return f"kast-host-release-v{version}{suffix}.json"


def sdk(value: object) -> SdkTarget | Failure:
    if not catalog.mapping(value) or set(value) != {
        "intellijReleaseLine",
        "ideBuild",
        "kotlinPluginBuild",
    }:
        return Failure.INVALID_TARGETS
    line, ide, kotlin = value["intellijReleaseLine"], value["ideBuild"], value["kotlinPluginBuild"]
    if (
        not isinstance(line, str)
        or line not in LINES
        or not isinstance(ide, str)
        or not re.fullmatch(rf"{line}\.[0-9]+\.[0-9]+", ide)
        or not isinstance(kotlin, str)
        or kotlin != f"{ide}-IJ"
    ):
        return Failure.INVALID_TARGETS
    return SdkTarget(line, ide, kotlin)


def decode_profile(value: object) -> Profile | Failure:
    if not catalog.mapping(value) or set(value) != {"schemaVersion", "defaultTarget", "targets"}:
        return Failure.INVALID_PROFILE
    values = value["targets"]
    if (
        type(value["schemaVersion"]) is not int
        or value["schemaVersion"] != 1
        or value["defaultTarget"] != "262"
        or not catalog.sequence(values)
    ):
        return Failure.INVALID_PROFILE
    targets: list[SdkTarget] = []
    for item in values:
        parsed = sdk(item)
        if isinstance(parsed, Failure):
            return Failure.INVALID_PROFILE
        targets.append(parsed)
    if tuple(target.intellijReleaseLine for target in targets) != LINES:
        return Failure.INVALID_PROFILE
    return Profile(1, "262", tuple(targets))


def target_sequence(value: object) -> TypeGuard[list[object] | tuple[object, ...]]:
    return isinstance(value, (list, tuple))


def decode_targets(value: object, version: str) -> tuple[HostTarget, ...] | Failure:
    if not target_sequence(value):
        return Failure.INVALID_TARGETS
    targets: list[HostTarget] = []
    for item in value:
        if not catalog.mapping(item) or set(item) != {
            "intellijReleaseLine",
            "ideBuild",
            "kotlinPluginBuild",
            "record",
        }:
            return Failure.INVALID_TARGETS
        target = sdk({key: field for key, field in item.items() if key != "record"})
        if isinstance(target, Failure):
            return target
        record = catalog.record(item["record"], record_name(version, target.intellijReleaseLine))
        if isinstance(record, catalog.Failure):
            return Failure.INVALID_TARGETS
        targets.append(
            HostTarget(
                target.intellijReleaseLine,
                target.ideBuild,
                target.kotlinPluginBuild,
                record,
            )
        )
    if tuple(target.intellijReleaseLine for target in targets) != LINES:
        return Failure.INVALID_TARGETS
    return tuple(targets)
