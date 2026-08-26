# Validation

Validated on 2026-08-21 against the bundled Kast 0.27.0 schema capture.

## Passed

- `scripts/docs.py` and `docs_macros.py` compile with Python 3.13.
- `python3 scripts/docs.py check --skip-source-existence` reports zero issues.
- The graph contains all eleven schema operations in schema order.
- Every operation has exactly one primary page.
- Every lifecycle command resolves to the runtime concept page.
- Repeated generation produces byte-identical generated outputs.
- The operation and source impact queries return the expected page sets.
- Every local Markdown page or JSON target exists.
- Every operation-linked page renders its CLI contract through the macro module.
- Front matter parses with PyYAML and with the restricted parser shape used by Code Knowledge Base.
- `zensical.toml` parses as TOML and every explicit navigation target exists.
- `zensical build --clean --strict` renders every page without warnings.
- Operation-page macros render exact CLI links through Zensical's `page.path` context.
- A localhost HTTP smoke test returns the rendered landing page.
