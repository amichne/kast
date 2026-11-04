# Rendered documentation evidence

This runner examines the real Mintlify preview through Agent Browser.
It keeps failed routes and unknown measurements in a publication receipt.
The runner does not call Kast or assess reader comprehension.

## Prepare the environment

Install the repository locks:

```shell
mise exec -- npm ci --ignore-scripts --engine-strict --prefix .github/tools/mintlify
mise exec -- npm ci --ignore-scripts --engine-strict --prefix docs/tooling/rendered
```

For Linux CI, install the exact Chrome version through the existing locked browser installer:

```shell
mise exec -- node .github/tools/mintlify/node_modules/@puppeteer/browsers/lib/cjs/main-cli.js install chrome@155.0.8059.84 --path build/documentation/browsers --install-deps
```

The installer needs root access for Linux system packages.
An existing exact-version Chrome installation also works with `--browser-executable`.
The runner rejects a different Node, Agent Browser, Chrome, or Mintlify version.

## Collect a candidate receipt

Complete source edits and commit the candidate before the final run.
Source changes during collection invalidate the receipt.

```shell
mise exec -- uv run --locked --script docs/tooling/rendered/run.py --preview --browser-executable build/documentation/browsers/chrome/linux-155.0.8059.84/chrome-linux64/chrome --output build/reports/documentation/rendered
```

The runner owns its preview process and browser session.
The browser starts after the owned server returns HTML.
The runner uses `lsof` and `ps` to bind the listener to the launched process and its descendants.
Missing inspection tools reject provenance.
Each browser effect examines that owner before and after its request.
Preview logs supply diagnostics.
It pins Mintlify client `0.0.3547` through the supported `--client-version` option.
It keeps at most 80 preview lines, with at most 500 characters per line.
Cleanup stops only its process group and named browser session.

`report.json` keeps the source inputs, measured states, font digests, screenshots, and failures.
`receipt.json` uses the publication owner's exact source snapshot.
Every cited report and screenshot has a digest.
Exit `0` needs all named obligations. Exit `1` keeps the rejected receipt.

Use `--origin` without `--preview` for diagnosis against an existing preview.
Use repeated `--route` arguments for a scoped run.
A partial route set cannot establish full publication acceptance.

## State scope and limits

The representative set contains Overview, Set up, a projected explanation, a callable, and the value explorer.
Each route uses desktop and mobile sizes, both themes, keyboard focus, and native browser zoom at 200 percent.
Chrome's settings API changes zoom in the disposable profile, then restores 100 percent.

The explorer scope contains four examples, five call contexts, and 18 step states.
Keyboard Enter must select each declared connection.
Each state reports rendered prose words, nonblank code lines, and prose in the initial viewport.
These per-state measurements do not replace the complete source footprint across all panels.

Callable measurements expand every graph edge through depth two, including every variant at that depth.
A reference counts as one edge. Deeper recursive fields remain outside this finite measurement.
The runner derives expected nodes and exact fact text from the canonical OpenAPI artifact.
It checks exposed DOM nodes, expanded controls, keyboard focus, and the artifact digest.
Missing, duplicated, hidden, changed, or stale scope evidence rejects publication.
A successful page load alone does not satisfy that obligation.

Screenshots supply review evidence. Their capture does not prove an accepted visual comparison.
Compare pixels only with equal platform, browser bytes, preview version, fonts, viewport, theme, and zoom.
The report keeps font digests and the browser executable digest for that decision.

## Tooling checks

```shell
mise exec -- uv run --locked --project docs/tooling pytest docs/tooling/rendered/test_run.py
mise exec -- uv run --locked --project docs/tooling pyright docs/tooling/rendered/run.py docs/tooling/rendered/test_run.py
mise exec -- uv run --locked --project docs/tooling ruff check --config docs/tooling/pyproject.toml docs/tooling/rendered/run.py docs/tooling/rendered/test_run.py
mise exec -- uv run --locked --project docs/tooling ruff format --check --config docs/tooling/pyproject.toml docs/tooling/rendered/run.py docs/tooling/rendered/test_run.py
```

The fixtures establish counts, malformed-input rejection, explicit effect failure, and rejection of partial evidence.
They do not establish browser behavior.
