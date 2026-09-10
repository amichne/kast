#!/usr/bin/env python3
"""Query the existing IDE endpoint. This client never starts an IDE or isolated indexer."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import stat
import struct
from dataclasses import dataclass
from enum import Enum
import jsonschema


class Failure(str, Enum):
    HOST_UNAVAILABLE = "HOST_UNAVAILABLE"
    DESCRIPTOR_REJECTED = "DESCRIPTOR_REJECTED"
    REQUEST_REJECTED = "REQUEST_REJECTED"
    RESPONSE_REJECTED = "RESPONSE_REJECTED"
    DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED"


@dataclass(frozen=True)
class Rejected:
    failure: Failure


@dataclass(frozen=True)
class Answer:
    document: dict


def strict_json(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("DUPLICATE_FIELD")
            result[key] = value
        return result
    def reject_constant(_):
        raise ValueError("NON_JSON_NUMBER")
    return json.loads(raw, object_pairs_hook=unique, parse_constant=reject_constant)


def valid_schema(document, filename):
    schema = json.loads((Path(__file__).parent / filename).read_text())
    return jsonschema.Draft202012Validator(schema).is_valid(document)


def exchange(root: Path, request: dict, home: Path) -> Answer | Rejected:
    try:
        root = root.resolve(strict=True)
        key = hashlib.sha256(str(root).encode()).hexdigest()[:32]
        directory = home / ".kast/ide-hosted" / key
        if not directory.exists():
            return Rejected(Failure.HOST_UNAVAILABLE)
        directory_stat = directory.stat()
        if directory.resolve() != directory or directory_stat.st_uid != os.getuid() or stat.S_IMODE(directory_stat.st_mode) != 0o700:
            return Rejected(Failure.DESCRIPTOR_REJECTED)
        descriptor = directory / "endpoint.json"
        if not descriptor.exists():
            return Rejected(Failure.HOST_UNAVAILABLE)
        if descriptor.is_symlink() or not descriptor.is_file() or descriptor.stat().st_size > 16_384:
            return Rejected(Failure.DESCRIPTOR_REJECTED)
        try:
            metadata = strict_json(descriptor.read_text())
        except ValueError:
            return Rejected(Failure.DESCRIPTOR_REJECTED)
        path = directory / "host.sock"
        if not valid_schema(metadata, "hosted-endpoint.schema.json") or metadata.get("type") != "KAST_IDE_ENDPOINT" or metadata.get("root") != str(root) or metadata.get("socket") != str(path):
            return Rejected(Failure.DESCRIPTOR_REJECTED)
        if path.is_symlink() or not stat.S_ISSOCK(path.stat().st_mode):
            return Rejected(Failure.DESCRIPTOR_REJECTED)
        data = json.dumps(request | {"root": str(root)}, separators=(",", ":")).encode()
        if len(data) > 16_384:
            return Rejected(Failure.REQUEST_REJECTED)
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(6)
            client.connect(str(path))
            client.sendall(struct.pack(">I", len(data)) + data)

            def receive(length):
                chunks = bytearray()
                while len(chunks) < length:
                    part = client.recv(length - len(chunks))
                    if not part:
                        raise EOFError
                    chunks.extend(part)
                return chunks

            length = struct.unpack(">I", receive(4))[0]
            if not 0 < length <= 65_536:
                return Rejected(Failure.RESPONSE_REJECTED)
            document = strict_json(receive(length).decode("utf-8", errors="strict"))
        if not isinstance(document, dict):
            return Rejected(Failure.RESPONSE_REJECTED)
        if document.get("type") == "HOST_REJECTED":
            if not valid_schema(document, "hosted-endpoint.schema.json"):
                return Rejected(Failure.RESPONSE_REJECTED)
        elif request["type"] == "DESCRIBE":
            if not valid_schema(document, "hosted-endpoint.schema.json") or document.get("type") != "KAST_IDE_HOST" or document.get("root") != str(root) or document.get("hostPid") != metadata.get("hostPid"):
                return Rejected(Failure.RESPONSE_REJECTED)
        else:
            if not valid_schema(document, "hosted-query.schema.json"):
                return Rejected(Failure.RESPONSE_REJECTED)
            if document["outcome"] == "published" and document["workspaceRoot"] != str(root):
                return Rejected(Failure.RESPONSE_REJECTED)
            if document["outcome"] == "published":
                if request["type"] == "CLASS_LOOKUP" and (document["kind"] != "classes" or document["name"] != request["name"]):
                    return Rejected(Failure.RESPONSE_REJECTED)
                if request["type"] == "DIRECT_SUPERTYPE" and (document["kind"] != "inheritors" or document["inheritor"]["file"] != str(root / request["file"])):
                    return Rejected(Failure.RESPONSE_REJECTED)
        return Answer(document)
    except (socket.timeout, TimeoutError):
        return Rejected(Failure.DEADLINE_EXCEEDED)
    except (EOFError, ValueError, KeyError, TypeError):
        return Rejected(Failure.RESPONSE_REJECTED)
    except OSError:
        return Rejected(Failure.HOST_UNAVAILABLE)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    operations = parser.add_subparsers(dest="operation", required=True)
    operations.add_parser("status")
    classes = operations.add_parser("classes")
    classes.add_argument("name")
    supertype = operations.add_parser("supertype")
    supertype.add_argument("file")
    supertype.add_argument("offset", type=int)
    args = parser.parse_args()
    if args.operation == "status":
        request = dict(type="DESCRIBE")
    elif args.operation == "classes":
        request = dict(type="CLASS_LOOKUP", name=args.name)
    else:
        request = dict(type="DIRECT_SUPERTYPE", file=args.file, offset=args.offset)
    result = exchange(args.root, request, Path.home())
    if isinstance(result, Rejected):
        print(json.dumps(dict(type="CLIENT_REJECTED", failure=result.failure.value)))
        return 1
    print(json.dumps(result.document, indent=2))
    return 2 if result.document.get("type") == "HOST_REJECTED" or result.document.get("outcome") == "rejected" else 0


if __name__ == "__main__":
    raise SystemExit(main())
