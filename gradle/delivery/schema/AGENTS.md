# Delivery JSON Schemas

These Draft 2020-12 schemas constrain the program projection, requirement trace, and live proof
receipts. Keep each schema closed where the document shape is known. `proof-receipt.schema.json`
requires receipt/base identities and nonempty string-valued observations; it must match the generated
Kotlin receipt serializer and typed admission model. A schema change is a delivery-contract change
and must be paired with typed Kotlin authority, regenerated projections when affected, and
dependency-free validation through `scripts/verify_bundle.py`.
