# Build, docs, and release

This page collects the repo-local commands that validate code, build the docs
site, and publish artifacts through the current workflow.

## Local build surface

Use the narrowest command that proves your change.

- `make build` compiles all Gradle modules.
- `make test` runs the Kotlin test suite.
- `make detekt` runs static analysis.
- `make check` mirrors the standard verification path used in CI.

## Documentation workflow

Docs live under `docs/` and are built with Zensical using `zensical.toml`.

- `make docs-install` creates `docs/venv` and installs the docs toolchain.
- `make docs-build` builds the static site into `site/`.
- `make docs-serve` runs the local docs server.
- `make docs-clean` removes generated docs output.

## Publishing workflow

Publishing is routed through the Makefile and repository scripts.

- `make validate-publish`
- `make publish-plan PUBLISH_TARGET=<local|snapshot|release|github>`
- `make publish-local`
- `make publish-snapshot`
- `make publish-release`
- `make publish-github`

## GitHub Actions

The current GitHub Actions workflow aligns with the same split.

- `CI` runs tests and builds the docs site.
- `Docs Pages` builds and deploys the Zensical site from `main`.
- `Publish Snapshot` validates and publishes snapshot artifacts.
- `Release` validates, publishes, and attaches release artifacts.

## Next steps

If you are done with lookup, the glossary and FAQ collect the small terms and
common objections that do not need their own full pages.

- [Open the appendix](../appendix/index.md)

