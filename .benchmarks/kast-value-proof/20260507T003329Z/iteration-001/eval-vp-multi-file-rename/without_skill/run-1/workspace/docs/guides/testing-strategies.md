# Testing strategies

Konditional is easiest to trust when the tests mirror the guarantees you care
about in production. Start with behavioral tests, not only declaration tests.

## Test the important invariants

The current repo's test suite is a good model for what to cover.

- **Determinism:** the same context and snapshot yield the same result
- **Namespace isolation:** similar keys in different namespaces do not bleed
  together
- **Boundary failure:** malformed or mismatched JSON returns `ParseResult`
  failures and keeps prior state
- **Atomicity:** concurrent readers see whole snapshots only

## Keep tests narrow

Most tests can use one tiny namespace defined inside the test and one or two
small context fixtures. You do not need a large application harness to prove
these behaviors.

## Useful repo examples

The current suite includes focused examples you can copy mentally.

- `NamespaceBehaviorTest`
- `NamespaceRuntimeTest`
- `NamespaceAtomicityTest`
- `NamespaceJsonTest`
- `ConfigurationCodecTest`

## Next steps

If you are introducing Konditional into an older string-key system, the last
guide shows a migration path that keeps those tests useful during rollout.

- [Read the migration guide](migrating-from-string-flags.md)

