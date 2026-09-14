"""Admit the hosted endpoint schema shipped inside the exact staged product jars."""
from dataclasses import dataclass
from enum import Enum
import hashlib
import json
from zipfile import ZipFile, BadZipFile
from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError


class HostedWireSchemaFailure(str, Enum):
    MISSING = 'HOSTED_WIRE_SCHEMA_MISSING'
    AMBIGUOUS = 'HOSTED_WIRE_SCHEMA_AMBIGUOUS'
    INVALID = 'HOSTED_WIRE_SCHEMA_INVALID'


class HostedWireSchemaRejected(ValueError):
    def __init__(self, failure):
        self.failure = failure
        super().__init__(failure.value)


@dataclass(frozen=True)
class HostedWireSchema:
    digest: str
    validator: Draft202012Validator

    def admits(self, document):
        return self.validator.is_valid(document)


def load_hosted_wire_schema(product):
    resource = 'ide-hosted/hosted-endpoint.schema.json'
    documents = []
    try:
        for jar in sorted((product / 'lib').glob('*.jar')):
            with ZipFile(jar) as archive:
                if resource in archive.namelist():
                    info = archive.getinfo(resource)
                    if not 1 <= info.file_size <= 1024 * 1024:
                        raise HostedWireSchemaRejected(HostedWireSchemaFailure.INVALID)
                    documents.append(archive.read(resource))
        if len(documents) != 1:
            raise HostedWireSchemaRejected(HostedWireSchemaFailure.MISSING if not documents
                                           else HostedWireSchemaFailure.AMBIGUOUS)
        raw = documents[0]
        schema = json.loads(raw)
        Draft202012Validator.check_schema(schema)
        return HostedWireSchema(hashlib.sha256(raw).hexdigest(), Draft202012Validator(schema))
    except (OSError, BadZipFile, ValueError, SchemaError) as error:
        if isinstance(error, HostedWireSchemaRejected):
            raise
        raise HostedWireSchemaRejected(HostedWireSchemaFailure.INVALID) from None
