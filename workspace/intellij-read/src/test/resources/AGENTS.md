# Project-read epoch expected report

This directory owns the independent exact-byte KVP-017 report contract.

- Keep `KVP-017-read-epoch.expected.json` canonical and byte-identical to the generated report only
  after product cases, finite failures, bounds, and forbidden-effect zeros are independently proven.
- Do not parse or rewrite this resource inside product tests; compare its bytes directly with the
  report supplied through `kast.ide.project.read.epoch.report`.
- Update the resource, report generator, negative mutations, engineering page, and receipt digest
  in one change when the closed schema changes.
