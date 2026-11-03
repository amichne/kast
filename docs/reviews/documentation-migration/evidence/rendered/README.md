# Rendered investigation evidence

These captures describe an investigation during the migration.
The source baseline is `7de04069e2a4a781a603ff51b3f9016b66108790`.
The investigation does not qualify the final candidate.

`investigation/report.json` records the owned preview run and its input mismatch.
Source edits continued during that run. The final candidate needs a new receipt.
The adjacent screenshots show the actual captured states.
`investigation/explorer-states.json` records the separate explorer pass.

## Executed results

Overview, Set up, the projected query explanation, and the value explorer passed the sampled layout assertions.
Each route used both themes, a 1440 × 1000 desktop viewport, and a 390 × 844 mobile viewport.
Native browser zoom at 200 percent passed those same four routes.
Keyboard focus remained visible in the sampled states.

The explorer run covered four examples, five call contexts, and 18 step states.
Each exposed state contained 725–805 prose words and 44 nonblank code lines.
The default state contained 797 prose words.
Each count covers its exposed state, rather than the union of every dynamic panel.
The report keeps initial-viewport prose separate.

The callable route `/reference/callables/query-symbols` failed to render.
The owned runner preserved that failure and marked the other four callable routes `not-run`.
Their rendered measurements remain unknown.
The publication receipt rejected the run.

## Renderer failure

`mint-4.2.841-failure.json` keeps the first bounded diagnostic and its evidence limits.
The preview repeatedly reported `Error processing schema: impossible schema combination`.
A separate HTTP request timed out after 15006 milliseconds with zero response bytes.
The first diagnostic did not preserve an exact input snapshot.
The later owned run captured its source inputs and rejected their drift.

A disposable Mintlify `4.2.1189` installation also reported the schema error.
That comparison does not justify a dependency update or a weaker schema.
Mintlify clients `0.0.3547` and `0.0.4050` supplied the two preview implementations.
The repository keeps its existing Mintlify pin.

## Review and limits

An author examined the desktop Overview and the dark mobile Set up capture.
The captures show readable text, the setup destination, and the prerequisite list.
The mobile prerequisite list extends beyond the initial viewport.
This review does not establish task completion or reader comprehension.

No accepted baseline pixel comparison ran.
The first screenshots came from local previews and do not prove deployment.
The new runner pins the preview client and records browser bytes and loaded font digests.
Future pixel comparisons need matching platform, environment, viewport, theme, and zoom.

Root Vale reported one grade advisory in `docs/tooling/rendered/README.md`.
`vale.json` keeps its rule and location.
The prose keeps exact command syntax, environment identities, and evidence limits despite the grade estimate.
This assessment does not prove reader comprehension.
