# PR 633 verifier support

This directory owns the repository-authority checks and focused tests used by
`.github/scripts/verify_pr633_program.py`. Keep the implementation standard-library-only and
fail closed on dependency, source-ledger, enforcer, gate-binding, CI-reuse, and retired-product
drift.

Run `python3 .github/scripts/pr633/test_verify_pr633_program.py` after changing this owner, then
run both `artifact` and `authorities` modes of the root verifier.
