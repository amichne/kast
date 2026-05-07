# Migrating from string flags

The safest migration is incremental. Do not try to replace every legacy flag
surface at once. Start by moving one business decision into a typed namespace
and keep the rest of the old system at the boundary.

## Phase 1: model the decision

Pick one decision that is already hard to read in its string-key form. Model
the return value as an enum or a `Konstrained` type, then define a namespace
that owns it.

This step proves the typed call-site value before you take on operational
changes.

## Phase 2: adapt the caller

Change one caller to consume the typed result from `evaluate(...)` instead of
switching directly on a legacy string or boolean.

That is where the migration starts paying back complexity immediately.

## Phase 3: dual-read at the edge if needed

If you need extra confidence, read both systems in the same application code
for a short period and log mismatches. Konditional does not currently ship a
built-in shadow-evaluation framework, so keep this comparison explicit and
temporary.

## Phase 4: move remote data to the parse boundary

When you are ready to replace legacy payload delivery, route external JSON
through `fromJson(json)` and handle `ParseResult` directly. This keeps the new
runtime state coherent even while the broader migration is still in flight.

## Next steps

Once the first migration path is working, the guarantee pages are the right
place to document why the new model is safer for your team.

- [Read the guarantees](../guarantees/index.md)
