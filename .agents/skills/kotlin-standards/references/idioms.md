# Kotlin Idioms

Use as a compact expression-style reminder.

Prefer `map`, `mapNotNull`, `flatMap`, `fold`, `associate`, `groupBy`,
`partition`, `filter`, `takeIf`, `let`, and sealed `when` when they state the
data flow directly.

Avoid transient `var`s, mutable accumulators, `apply` for non-configuration
work, `also` chains that hide important side effects, and Java-shaped null
handling when Kotlin has a clearer construct.
