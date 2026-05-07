## Prompt
Find callers of ConditionalValue.ContextualResolver.resolve(), not every function named resolve in the project. Show the call hierarchy.

## Tool calls
1. bash: `date +%s` - Record start time
2. bash: `find /tmp/kast-vp-rebench/konditional-without -type f -name "*.kt" -o -name "*.java" | head -20`
3. bash: `grep -r "class ContextualResolver" /tmp/kast-vp-rebench/konditional-without --include="*.kt"`
4. bash: `grep -n "fun resolve" /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt | grep -A 2 -B 2 "ContextualResolver"`
5. bash: `sed -n '/class ContextualResolver/,/^[[:space:]]*}/p' /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt`
6. bash: `grep -r "ContextualResolver" /tmp/kast-vp-rebench/konditional-without --include="*.kt" -n`
7. bash: `wc -l /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt`
8. bash: `cat /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt`
9. bash: `grep -r "\.resolve(" /tmp/kast-vp-rebench/konditional-without --include="*.kt" -n | grep -v "test" | grep -v "staticValueOrNull"`
10. bash: `sed -n '160,170p' /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt`
11. bash: `sed -n '140,180p' /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt`
12. bash: `grep -r "targetedBy" /tmp/kast-vp-rebench/konditional-without --include="*.kt" -n | head -20`
13. bash: `grep -r "\.resolve(" /tmp/kast-vp-rebench/konditional-without --include="*.kt" -n`
14. bash: `grep -r "evaluateCandidate" /tmp/kast-vp-rebench/konditional-without --include="*.kt" -n`
15. bash: `sed -n '100,135p' /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt`
16. bash: `grep -r "evaluateTrace" /tmp/kast-vp-rebench/konditional-without --include="*.kt" -n`
17. bash: `sed -n '65,85p' /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt`
18. bash: `grep -r "\.evaluate(" /tmp/kast-vp-rebench/konditional-without --include="*.kt" -n | grep -v "// " | head -30`
19. bash: `sed -n '125,145p' /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt`
20. bash: `grep -r "createRuleDiagnostics" /tmp/kast-vp-rebench/konditional-without --include="*.kt" -n`
21. bash: `sed -n '100,125p' /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt`
22. bash: `sed -n '85,120p' /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt`
23. bash: `grep -r "createBaseDiagnostics" /tmp/kast-vp-rebench/konditional-without --include="*.kt" -n`
24. bash: `sed -n '75,90p' /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt`
25. bash: `grep -r "evaluateInternal" /tmp/kast-vp-rebench/konditional-without --include="*.kt" -n`
26. bash: `sed -n '35,80p' /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/api/FeatureEvaluation.kt`
27. bash: `grep -r "\.resolve\|resolver\.resolve\|ContextualResolver" /tmp/kast-vp-rebench/konditional-without --include="*.kt" -C 2 | grep -v "^--$" | head -60`
28. bash: `date +%s` - Record end time

## Final answer

### Call Hierarchy for ConditionalValue.ContextualResolver.resolve()

The `resolve()` method of `ConditionalValue.ContextualResolver` is invoked through the following call hierarchy:

**Direct call path:**
```
evaluate() / explain()
  → evaluateInternal() [FeatureEvaluation.kt:45/63]
    → evaluateInternal() [FeatureEvaluation.kt:71]
      → createBaseDiagnostics() [FeatureEvaluation.kt:85]
        → createRuleDiagnostics() [FeatureEvaluation.kt:119]
          → evaluateTrace() [FeatureEvaluation.kt:134]
            → evaluateCandidate() [FlagDefinition.kt:127]
              → candidate.resolve() [FlagDefinition.kt:165]
                → ConditionalValue.resolve() [ConditionalValue.kt:39]
                  → resolver.resolve() [ConditionalValue.kt:39]
                    → ContextualResolver.resolve() [ConditionalValue.kt:69]
```

**Key findings:**

1. **Entry points** (public API):
   - `Feature<T, C, M>.evaluate(context, registry)` [FeatureEvaluation.kt:45]
   - `Feature<T, C, M>.explain(context, registry)` [FeatureEvaluation.kt:63]

2. **Internal evaluation pipeline**:
   - `evaluateInternal()` is an overloaded function that delegates to the full signature version
   - `createBaseDiagnostics()` routes to `createRuleDiagnostics()` for active, enabled flags
   - `createRuleDiagnostics()` calls `evaluateTrace()` to perform actual evaluation

3. **Candidate evaluation**:
   - `evaluateTrace()` [FlagDefinition.kt:102] iterates through `valuesByPrecedence` collection
   - For each candidate `ConditionalValue`, it calls `evaluateCandidate()` [FlagDefinition.kt:127]
   - `evaluateCandidate()` calls `candidate.resolve()` when the rule matches [FlagDefinition.kt:165]

4. **Resolver dispatch**:
   - `ConditionalValue.resolve()` receives `context`, `registry`, and `ownerNamespace`
   - It dispatches to the internal resolver implementation through polymorphism
   - If the resolver is a `ContextualResolver` instance, `ContextualResolver.resolve()` is invoked
   - `ContextualResolver.resolve()` creates a `RuleValueScope` and evaluates it

**Callers of ConditionalValue.ContextualResolver.resolve():**
- Only invoked indirectly through `ConditionalValue.resolve()` when a `ContextualResolver` instance is the active resolver
- The resolver is created at rule-definition time via `Rule<C>.targetedBy(valueResolver)` [ConditionalValue.kt:89-92]
- Creation sites: FlagBuilder.kt (lines 114, 125) and FlagDefinitionInternal.kt (line 86)
