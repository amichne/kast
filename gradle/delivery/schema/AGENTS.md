# Delivery JSON Schemas

These Draft 2020-12 schemas constrain the program projection, requirement trace, and live proof
receipts. Keep each schema closed where the document shape is known. `proof-receipt.schema.json`
requires receipt/base identities and nonempty string-valued observations; it must match the generated
Kotlin receipt serializer and typed admission model. A schema change is a delivery-contract change
and must be paired with typed Kotlin authority, regenerated projections when affected, and
dependency-free validation through `scripts/verify_bundle.py`.

`ide-endpoint.schema.json` owns the closed 14-field `kast.ide.endpoint.v2` document. It fixes the
IDE Project host, framing, and ordered four-operation capability set; admits only positive process
IDs and non-negative runtime epochs; and constrains root and socket text to normalized absolute
POSIX paths. The wire codec additionally enforces the Unix-domain socket's 103-byte UTF-8 limit,
which JSON Schema cannot express from character count alone.
