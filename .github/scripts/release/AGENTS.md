# Release script guide

This directory owns release asset construction, publication, asset validation,
and the contract that keeps those stages releasable from main.

- `verify-release-contract.py` must run in pull-request CI and at release start.
- The public installer recovery contract must run in pull-request CI and at
  release start, before asset construction.
- The default release contains exactly the matched control archive and standalone IDE plugin plus
  checksum sidecars; it contains no semantic-runtime asset or platform payload.
- The hosted-release misuse and legal gates must finish before immutable publication.
- Published-hosted verification may own download and checksum checks, but it
  must acquire through `install.sh`, prove the installed control-plus-plugin
  pair, and prove missing endpoint demand fails without an isolated process.
- Do not duplicate public operation sequences in a release-only script.

Run:

```console
python3 .github/scripts/release/verify-release-contract.py --root .
bash -n .github/scripts/release/*.sh
actionlint .github/workflows/ci.yml .github/workflows/release.yml
```
