# Kotlin Idioms

Use as a compact expression-style reminder.

Prefer `map`, `mapNotNull`, `flatMap`, `fold`, `associate`, `groupBy`,
`partition`, `zip`, `buildList`, `filter`, `takeIf`, `let`, and exhaustive sealed `when` when they state the data flow
directly.

Use higher-order functions when they make the policy clear: how values are classified, mapped, folded, ordered,
filtered, retried, or wrapped. Name the behavior with a local function or well-named parameter when the lambda carries
business meaning.

Avoid transient `var`s, mutable accumulators, `apply` for non-configuration work, `also` chains that hide important side
effects, callback soup, and Java-shaped null handling when Kotlin has a clearer construct.
