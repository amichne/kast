# Public documentation guidance

## Ownership

This directory owns the Zensical source for `https://kast.michne.com`, its
reader-facing content checks, the root GitHub README contract, publication
boundary, and LikeC4 sources.
`zensical.toml` at the repository root is the site and navigation authority.
`kast-vfs-passive-reused-index-delivery-program.md` is the normative plan
projected from the typed delivery authority. It is not a public-site route and
must keep its exact target head and program fingerprint aligned with
`gradle/delivery`.

The site has five reader routes: Start, Repository questions, Trust the
evidence, CLI reference, and How Kast works. Keep the landing page quiet and
question-led. Use semantic color to distinguish discovery, exact identity,
evidence, and effects. Do not turn the site into a product pitch or expose the
module graph before the reader understands the runtime flow.

## Source and generated boundaries

- Author human-facing pages under `public/`.
- Keep internal supported-build engineering evidence under `engineering/`. These files are
  delivery evidence, not public-site routes. `engineering/ide-read-epoch-ledger.md` records the
  KVP-015 IDEA 262 epoch-signal characterization and must match its generated JSON report.
  `engineering/ide-detached-model.md` records KVP-016 detached-model capture and must match its
  generated report, exact limits, and no-production-epoch boundary.
- Generate `public/reference/cli.md` from the typed operation-registry artifact with
  `./gradlew :protocol:wire:generateOperationRegistry` followed by
  `python3 docs/generate_cli_reference.py`. Do not edit that page by hand.
- Edit the LikeC4 contract in `public/architecture/{specification,model,views}.c4`.
- Generate `public/architecture/likec4-views.mjs` with:

  ```shell
  python3 docs/tooling/likec4/generate_bundle.py
  ```

  The generator records the exact npm lockfile and semantic-model fingerprints.
  Its check mode compares every authored model field with LikeC4's own
  compute-only JSON export and validates the complete layouted module wrapper
  separately. Tool-owned view hashes, workspace-derived relationship IDs,
  layout geometry, and third-party minification are required generated
  structure but are not byte-stable across supported hosts. Relationship
  semantics remain exact after re-keying each relationship from its complete
  content.

- Keep authored pages focused on a reader decision or outcome. The generated
  CLI page is the only command reference. `kast --schema` remains the
  machine-readable public authority.
- Keep the root `README.md` as a concise GitHub entry point. Link to the
  generated CLI reference for exhaustive lookup. `test_public_docs.py` checks
  every README command path against that reference. It also keeps the README
  and Start page on the same one-command public installer.
- Build the publishable artifact with `python3 docs/build_public_site.py`. The
  script stages reader assets before invoking Zensical so local `AGENTS.md`
  files and authored LikeC4 sources do not become public routes or assets.
- `.github/workflows/docs.yml` runs the content contract and the same public
  site builder before it uploads `site/` for GitHub Pages.
- Use current canonical operation names. Do not claim runtime data flow,
  completeness, or safe mutation beyond the evidence returned by the relevant
  operation.

## Focused proof

Run these checks after changing this directory:

```shell
./gradlew :protocol:wire:generateOperationRegistry
python3 docs/generate_cli_reference.py --check
python3 docs/tooling/likec4/generate_bundle.py --check
python3 docs/test_public_docs.py
docs/tooling/likec4/node_modules/.bin/likec4 validate --json --no-layout \
  --file docs/public/architecture/specification.c4 \
  --file docs/public/architecture/model.c4 \
  --file docs/public/architecture/views.c4 \
  docs/public/architecture
python3 docs/build_public_site.py
```

Inspect the built homepage and How Kast works page at desktop and mobile
widths after structural, navigation, CSS, or diagram changes.
