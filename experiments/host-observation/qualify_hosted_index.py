#!/usr/bin/env python3
"""Qualify incremental IDE-index reads with one owned, restored Kotlin fixture in Kast."""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import tempfile
import time
import uuid
from kast_ide import Answer, exchange


def run(root, idea, with_supertype=False):
    root = root.resolve(strict=True)
    launcher = idea / "MacOS/idea"
    evidence = Path(tempfile.mkdtemp(prefix="kast-ide-incremental-"))
    print(f"Evidence: {evidence}", flush=True)
    identity = exchange(root, dict(type="DESCRIBE"), Path.home())
    if not isinstance(identity, Answer) or identity.document.get("type") != "KAST_IDE_HOST":
        raise RuntimeError("HOST_UNAVAILABLE")
    name = "KastHostedIndexAcceptance" + uuid.uuid4().hex[:12]
    before, after = name + "Before", name + "After"
    parent = name + "Base"
    fixture = root / "kernel/src/main/kotlin" / (name + ".kt")

    def content(class_name, copies=1):
        return (f"open class {parent}\n" + f"class {class_name} : {parent}()\n" * copies
                if with_supertype else f"class {class_name}\n")

    expected = content(before)
    observations = []

    def refresh():
        directory = Path(tempfile.mkdtemp(prefix="refresh-", dir=evidence))
        source = directory / "input.json"
        source.write_text(json.dumps(dict(root=str(root), file=str(fixture))))
        template = (Path(__file__).parent / "hosted-index-fixture.kts.template").read_text()
        script = directory / "refresh.kts"
        script.write_text(template.replace("@INPUT_BASE64@", base64.b64encode(str(source).encode()).decode()))
        carrier = directory / "carrier.json"
        carrier.write_text(json.dumps(dict(stage="FIXTURE_REFRESH", outcome="UNCONFIRMED")))
        subprocess.run([str(launcher), "ideScript", str(script)], check=True, timeout=30, stdout=subprocess.DEVNULL)
        deadline = time.monotonic() + 30
        receipt = directory / "refreshed.json"
        while not receipt.exists():
            if time.monotonic() >= deadline:
                raise RuntimeError("REFRESH_UNCONFIRMED")
            time.sleep(0.1)
        proof = json.loads(receipt.read_text())
        if proof["hostPid"] != identity.document["hostPid"] or proof["root"] != str(root):
            raise RuntimeError("ORIGINAL_HOST_LOST")
        carrier.write_text(json.dumps(dict(stage="FIXTURE_REFRESH", outcome="COMPLETED")))

    def query(class_name, count):
        deadline = time.monotonic() + 30
        while True:
            answer = exchange(root, dict(type="CLASS_LOOKUP", name=class_name), Path.home())
            if isinstance(answer, Answer) and answer.document.get("outcome") == "published":
                found = answer.document["declarations"]
                if len(found) != count:
                    raise RuntimeError("INDEX_CONTENT_MISMATCH")
                if count and (found[0]["signature"]["qualifiedIdentity"] != class_name or found[0]["file"] != str(fixture)):
                    raise RuntimeError("COMPILER_IDENTITY_MISMATCH")
                observations.append(dict(name=class_name, count=count, indexAuthority=answer.document["indexAuthority"]))
                return
            if not isinstance(answer, Answer) or answer.document.get("failure") not in ("INDEXING", "FRESHNESS_REJECTED", "PROJECT_ADMISSION_REJECTED") or time.monotonic() >= deadline:
                failure = (dict(boundary="HOST", failure=answer.document.get("failure"), stage=answer.document.get("stage"), detail=answer.document.get("detail"))
                           if isinstance(answer, Answer) else dict(boundary="CLIENT", failure=answer.failure.value))
                (evidence / "rejection.json").write_text(json.dumps(failure, indent=2) + "\n")
                raise RuntimeError("INDEX_UNAVAILABLE")
            time.sleep(0.2)

    def supertype(class_name, expected_failure=None):
        deadline = time.monotonic() + 30
        while True:
            answer = exchange(root, dict(type="DIRECT_SUPERTYPE", qualifiedName=class_name), Path.home())
            if isinstance(answer, Answer):
                document = answer.document
                if expected_failure is not None and document.get("outcome") == "rejected" and document.get("failure") == expected_failure:
                    observations.append(dict(qualifiedName=class_name, failure=expected_failure, stage=document["stage"]))
                    return
                if expected_failure is None and document.get("outcome") == "published":
                    if (document["supertype"]["signature"]["qualifiedIdentity"] != parent or
                            document["inheritor"]["file"] != str(fixture) or document["supertype"]["file"] != str(fixture)):
                        raise RuntimeError("SUPERTYPE_IDENTITY_MISMATCH")
                    observations.append(dict(qualifiedName=class_name, supertype=parent, stage=document["stage"]))
                    return
            if not isinstance(answer, Answer) or answer.document.get("failure") not in ("INDEXING", "FRESHNESS_REJECTED", "PROJECT_ADMISSION_REJECTED") or time.monotonic() >= deadline:
                failure = (dict(boundary="HOST", failure=answer.document.get("failure"), stage=answer.document.get("stage"), detail=answer.document.get("detail"))
                           if isinstance(answer, Answer) else dict(boundary="CLIENT", failure=answer.failure.value))
                (evidence / "rejection.json").write_text(json.dumps(failure, indent=2) + "\n")
                raise RuntimeError("SUPERTYPE_UNAVAILABLE")
            time.sleep(0.2)

    def replace(text):
        nonlocal expected
        if fixture.read_text() != expected:
            raise RuntimeError("OWNED_FIXTURE_CHANGED")
        expected = text
        fixture.write_text(expected)
        refresh()

    with fixture.open("x") as file:
        file.write(expected)
    try:
        refresh()
        query(before, 1)
        query(after, 0)
        if with_supertype:
            supertype(before)
        replace(content(after))
        query(before, 0)
        query(after, 1)
        if with_supertype:
            supertype(before, "DECLARATION_NOT_FOUND")
            supertype(after)
            replace(content(after, copies=2))
            supertype(after, "AMBIGUOUS_DECLARATION")
            replace(content(after))
            supertype(after)
    finally:
        if fixture.read_text() != expected:
            raise RuntimeError("CLEANUP_OWNERSHIP_LOST")
        fixture.unlink()
        (evidence / "cleanup.txt").write_text("DISK_REMOVED")
        refresh()
        (evidence / "cleanup.txt").write_text("REMOVED")
    query(after, 0)
    if with_supertype:
        supertype(after, "DECLARATION_NOT_FOUND")
    final_identity = exchange(root, dict(type="DESCRIBE"), Path.home())
    if not isinstance(final_identity, Answer) or final_identity.document != identity.document:
        raise RuntimeError("ORIGINAL_HOST_LOST")
    report = dict(outcome="VERIFIED", hostPid=identity.document["hostPid"], root=str(root), observations=observations, cleanup="REMOVED")
    (evidence / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--idea-contents", type=Path, required=True)
    parser.add_argument("--with-supertype", action="store_true")
    args = parser.parse_args()
    run(args.root, args.idea_contents, args.with_supertype)
