# Scope

- This file applies to `scripts/`.

## Work here

- Verify changes with `./scripts/publish-on-rails.sh --help`, `./scripts/validate-publish.sh local`, and `make publish-gradle-validate TARGET=local`.

## Edit rules

- Keep target names and credential variable names aligned with `Makefile`, `.github/workflows/snapshot.yml`, and `.github/workflows/release.yml`.
- Prefer validation or help paths over real publish targets while iterating.
