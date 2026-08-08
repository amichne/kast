# Typed public protocol runbook

<!-- Generated from the typed public operation registry. -->

1. Establish evidence with `kast workspace ensure`.
2. Discover uncertainty with `kast symbol search --query Widget` or resolve exact text with `kast symbol resolve --query 'example.Widget.render()'`.
3. Copy the emitted selector verbatim into `kast symbol show --selector <SELECTOR>` and `kast relation references --selector <SELECTOR>`.
4. Repeat a paged operation with its own returned `--continuation`; never move it to another operation.
5. Create a selector-bound plan with `kast change plan rename --selector <SELECTOR> --name Renamed`. Apply only its returned plan ID with `kast change apply --plan-id <PLAN_ID>`.

The workflow rejects qualified names, locations, paths, offsets, graph node selectors, stale selectors, wrong-root selectors, and cross-operation continuations before semantic execution or mutation planning.
