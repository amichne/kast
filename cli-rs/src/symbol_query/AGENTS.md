# Symbol Query Module Instructions

This directory contains direct source-index symbol query reads that must
migrate behind typed backend APIs under
`.agents/adr/0023-signed-idea-plugin-distribution-and-runtime-authority.md`.
Do not add new direct database reads, query modes, or consumers here. Until its
follow-on migration lands, changes are limited to correctness fixes required
to preserve the existing surface or to remove it.

Keep request/response models, database reads, ranking/filtering, and tests
separated. Ranking changes remain explainable through typed signals in the
response.

Semantic, lexical, graph, and structural signals stay visible as individual
evidence. New typed symbol-query contracts belong in `analysis-api` and are
served by the active backend through `analysis-server`.
