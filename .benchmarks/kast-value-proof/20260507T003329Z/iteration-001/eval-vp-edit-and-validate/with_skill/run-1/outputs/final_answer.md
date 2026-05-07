Worked in disposable copy only: `/Users/amichne/code/kast/.benchmarks/kast-value-proof/20260507T003329Z/iteration-001/eval-vp-edit-and-validate/with_skill/run-1/workspace` (original checkout untouched).

Applied edit:

```kotlin
@Deprecated("Use FeatureRegistry instead")
interface NamespaceRegistry {
```

Validation:

- Kast apply-edits succeeded on the disposable file, and a follow-up diagnostics pass on `NamespaceRegistry.kt` returned **zero diagnostics**.
- I could not get a trustworthy Gradle compile confirmation from this environment because `:konditional-engine:compileKotlin --offline` fails before task execution on both the untouched original repo and the disposable copy with the same pre-existing error: `What went wrong: 25`.

So the file-level semantic validation is clean after the edit, but repo-level compile confirmation is blocked by an existing environment/toolchain issue, not by the annotation edit.
