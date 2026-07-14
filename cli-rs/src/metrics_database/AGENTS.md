# Metrics Database Instructions

This directory owns direct SQLite metrics queries over the source-index cache.

Keep query controls and result models separate from SQL execution and helper
serialization. Public functions should return typed direct metrics results or
typed direct metrics errors.

Presentation belongs in `output` or the calling command.

Impact identity is fail-closed. Verify the compiler anchor, then require a
production declaration row matching FQ name, canonical path, non-null offset,
and kind. The production declaration primary key cannot represent same-FQ
same-file overloads, so functions and properties must return typed overload
granularity degradation rather than aggregate FQ edges. A declaration-row FQ
count may reject additional stored non-callable rows; it is never callable
overload proof.
