# Install

The smallest useful dependency for a first adoption is `konditional-engine`.
It gives you `Namespace`, typed feature declarations, `evaluate(...)`, and
`explain(...)`.

## Add the engine module

Start in the Gradle module that owns the first feature decision.

```kotlin
dependencies {
    implementation("io.amichne.konditional:konditional-engine:<version>")
}
```

That is enough to declare flags and evaluate them in application code.

## Add JSON later

You only need `konditional-json` when you are ready to export or import
namespace snapshots.

```kotlin
dependencies {
    implementation("io.amichne.konditional:konditional-json:<version>")
}
```

The JSON module builds on top of the engine model. You do not need it for the
first namespace or the first typed evaluation path.

## Verify the setup

Compile the module after adding the dependency so the rest of the quickstart
starts from a known-good baseline.

```bash
./gradlew <your-module>:build
```

## Next steps

With the dependency in place, the next step is to model one real domain value,
one context, and one namespace.

- [Define your first namespace](define-first-namespace.md)

