# Symbol Query Module Instructions

This directory owns the JSON-RPC symbol query implementation backed by the
source-index database.

Keep RPC wrapping, request/response models, database reads, ranking/filtering,
and tests separated. Ranking changes must remain explainable through typed
signals in the response.

Do not collapse semantic, lexical, graph, and structural signals into a single
score without preserving their individual evidence.
