#!/usr/bin/env python3
"""Validate the pinned IntelliJ add-declaration physical protocol ledger."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import NoReturn


RUNTIME_BUILD = re.compile(r"[0-9]+(?:\.[0-9]+){2}")
TOP_LEVEL_KEYS = {
    "type", "schemaVersion", "ownerTicket", "runtimeBuild", "distributionVersion",
    "productFamily", "supportState", "selectedExecutor", "planInputs", "observedBehavior",
    "closedLimitations", "evidence",
}
PUBLIC_APIS = [
    "org.jetbrains.kotlin.psi.KtPsiFactory.createDeclaration",
    "com.intellij.psi.PsiElement.add",
    "com.intellij.psi.codeStyle.CodeStyleManager.reformat(PsiElement,boolean)",
    "com.intellij.openapi.command.WriteCommandAction.writeCommandAction(Project,PsiFile...)",
    "com.intellij.psi.PsiDocumentManager.commitDocument",
    "com.intellij.openapi.fileEditor.FileDocumentManager.saveDocument",
]
PREPARATION = [
    "CHECK_CANCELLATION", "REQUIRE_SMART_MODE", "REQUIRE_WRITABLE_TARGET",
    "RESOLVE_EXACT_TARGET", "PARSE_DECLARATION", "CAPTURE_DOCUMENT",
]
INSIDE_COMMAND = [
    "ADD_DECLARATION_PSI", "REFORMAT_WHITESPACE_ONLY", "COMMIT_TARGET_DOCUMENT",
]
AFTER_COMMAND = [
    "SAVE_TARGET_DOCUMENT", "OBSERVE_CHANGED_DOCUMENT_SET", "CHECK_GLOBAL_UNDO",
]
FORBIDDEN_INSIDE_COMMAND = [
    "INDEX_OR_SEARCH", "SMART_MODE_WAIT", "VFS_REFRESH", "GRADLE_IMPORT", "PERSISTENCE",
    "VERIFICATION", "DOCUMENT_SAVE", "REFERENCE_SHORTENING",
]
PLAN_INPUTS = [
    "canonicalTargetPath", "targetPreimageSha256", "semanticGeneration", "compiledSourceOwner",
    "insertionAnchor", "declarationText", "expectedPostimageSha256", "formatWhitespaceOnly",
    "declaredWriteSet",
]
OBSERVED_BEHAVIOR = {
    "affectedDocuments": "EXACT_DECLARED_TARGET",
    "commandThread": "EDT_WITH_WRITE_ACCESS",
    "commandDuration": "MEASURED_SEPARATELY",
    "undo": "UNAVAILABLE_IN_HEADLESS_INDEXER",
    "formatting": "WHITESPACE_ONLY",
    "referenceShortening": "NOT_PERFORMED",
    "save": "EXPLICIT_AFTER_COMMAND",
    "cancellation": "PROPAGATES_BEFORE_COMMAND",
    "dumbMode": "REJECTED_BEFORE_COMMAND",
    "readOnly": "REJECTED_BEFORE_COMMAND",
}
LIMITATION_CODES = {
    "ANDROID_STUDIO_UNVERIFIED",
    "UNPINNED_BUILD_UNVERIFIED",
    "REFERENCE_SHORTENING_REQUIRES_SEPARATE_PROTOCOL",
    "MID_COMMAND_CANCELLATION_NOT_ADMITTED",
    "HEADLESS_UNDO_UNAVAILABLE",
}
EVIDENCE = [
    "indexer/src/test/kotlin/io/github/amichne/kast/idea/backend/contract/mutation/addition/AddDeclarationIntellijProtocolTest.kt",
]


def reject(code: str, message: str) -> NoReturn:
    print(f"invalid: {code}: {message}", file=sys.stderr)
    raise SystemExit(1)


def require(condition: bool, code: str, message: str) -> None:
    if not condition:
        reject(code, message)


def exact_object(value: object, keys: set[str], code: str) -> dict[str, object]:
    require(isinstance(value, dict), code, "expected object")
    require(set(value) == keys, code, "object keys drift")
    return value


def exact_list(value: object, expected: list[str], code: str) -> None:
    require(value == expected, code, "ordered values drift")


def validate(ledger: dict[str, object]) -> None:
    exact_object(ledger, TOP_LEVEL_KEYS, "SCHEMA")
    require(ledger["type"] == "KAST_ADD_DECLARATION_INTELLIJ_PROTOCOL", "SCHEMA", "type drift")
    require(ledger["schemaVersion"] == 1, "SCHEMA", "version drift")
    require(ledger["ownerTicket"] == "KIP-030", "SCHEMA", "owner drift")
    runtime_build = ledger["runtimeBuild"]
    require(runtime_build == "261.25134.95", "RUNTIME", "unproven runtime build")
    require(isinstance(runtime_build, str) and RUNTIME_BUILD.fullmatch(runtime_build) is not None, "RUNTIME", "invalid build")
    require(ledger["distributionVersion"] == "2026.1.3", "RUNTIME", "distribution drift")
    require(ledger["productFamily"] == "INTELLIJ_IDEA_COMMUNITY", "RUNTIME", "product drift")
    require(ledger["supportState"] == "PINNED_BUILD_ONLY", "RUNTIME", "support scope widened")

    executor = exact_object(
        ledger["selectedExecutor"],
        {"kind", "status", "publicApis", "preparationOutsideWriteCommand", "insideWriteCommand",
         "afterWriteCommand", "forbiddenInsideWriteCommand"},
        "EXECUTOR",
    )
    require(executor["kind"] == "KOTLIN_PSI_INSERTION", "EXECUTOR", "executor kind drift")
    require(executor["status"] == "SUPPORTED", "EXECUTOR", "executor status drift")
    exact_list(executor["publicApis"], PUBLIC_APIS, "EXECUTOR_API")
    exact_list(executor["preparationOutsideWriteCommand"], PREPARATION, "WRITE_BOUNDARY")
    exact_list(executor["insideWriteCommand"], INSIDE_COMMAND, "WRITE_BOUNDARY")
    exact_list(executor["afterWriteCommand"], AFTER_COMMAND, "WRITE_BOUNDARY")
    exact_list(executor["forbiddenInsideWriteCommand"], FORBIDDEN_INSIDE_COMMAND, "WRITE_BOUNDARY")
    exact_list(ledger["planInputs"], PLAN_INPUTS, "PLAN_INPUT")
    require(ledger["observedBehavior"] == OBSERVED_BEHAVIOR, "BEHAVIOR", "observation drift")

    limitations = ledger["closedLimitations"]
    require(isinstance(limitations, list), "LIMITATION", "limitations must be a list")
    require(all(isinstance(item, dict) and set(item) == {"code", "effect"} for item in limitations), "LIMITATION", "limitation shape drift")
    require({item["code"] for item in limitations} == LIMITATION_CODES, "LIMITATION", "limitation set drift")
    require(all(item["effect"] == "UNSUPPORTED" for item in limitations), "LIMITATION", "limitation effect drift")
    exact_list(ledger["evidence"], EVIDENCE, "EVIDENCE")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ledger", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        ledger = json.loads(arguments.ledger.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        reject("READ", str(failure))
    require(isinstance(ledger, dict), "SCHEMA", "ledger must be an object")
    validate(ledger)
    print("KIP-030 add-declaration IntelliJ protocol: valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
