# `evaluation/runners/`

This directory holds **runner adapters** for the evaluation framework — thin
scripts that bridge `evaluation/scripts/dispatch_runs.py`'s
`--command-template` contract to a concrete agent or CLI.

Adapters live here; framework logic (rendering, scaffolding, dispatching,
grading, aggregating) stays under `evaluation/scripts/`. If a change needs
new placeholders, new run-discovery rules, or new manifest fields, that is
a framework change and belongs in `evaluation/scripts/`, not here.
