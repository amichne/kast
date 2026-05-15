# Kast routing improvement

`evals/catalog.json` is the canonical routing and behavior suite for the Kast skill. Keep native-tool routing cases here
with `expected_route` set to
`native-kast-tools`, `allowed_ops` set to `kast_*` wrapper names, and
`forbidden_ops` covering generic Kotlin tools such as `grep`, `rg`, and `view`.

Use `scripts/build-routing-corpus.py` to regenerate or extend routing cases from maintenance inputs. The historical
maintenance assets remain under
`fixtures/maintenance/` for reference, but the durable CI-scored corpus lives in
`evals/` and `history/`.
