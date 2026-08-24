# Release script guide

This directory owns release asset construction, publication, asset validation,
and the contract that keeps those stages releasable from main.

- `verify-release-contract.py` must run in pull-request CI and at release start.
- The public installer recovery contract must run in pull-request CI and at
  release start, before asset construction.
- Build and installed-product acceptance must finish before immutable publication.
- Published-runtime verification may own download and checksum checks, but it
  must acquire through `install.sh`, disable the manifest network URL after
  installation, and delegate semantic behavior to
  `packaging/topology_installed_acceptance.py`.
- Do not duplicate public operation sequences in a release-only script.

Run:

```console
python3 .github/scripts/release/verify-release-contract.py --root .
bash -n .github/scripts/release/*.sh
actionlint .github/workflows/ci.yml .github/workflows/release.yml
```
