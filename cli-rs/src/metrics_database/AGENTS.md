# Metrics Database Instructions

This directory owns direct SQLite metrics queries over the source-index cache.

Keep query controls and result models separate from SQL execution and helper
serialization. Public functions should return typed direct metrics results or
typed direct metrics errors.

Presentation belongs in `output` or the calling command.
