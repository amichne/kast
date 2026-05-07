## Prompt

Find callers of ConditionalValue.ContextualResolver.resolve(), not every function named resolve in the project. Show the call hierarchy.

## Tool calls

1. bash: `date +%s` (record start time)
2. bash: `mkdir -p` (create output directory)
3. kast_resolve: resolve ContextualResolver with containingType (FAILED - wrong FQ name format)
4. kast_callers: find callers of resolve in ContextualResolver (FAILED - same reason)
5. bash: `find` (locate ConditionalValue.kt file)
6. kast_scaffold: analyze ConditionalValue.kt to find correct FQ names
7. bash: `head` (view scaffold output to identify ContextualResolver structure)
8. bash: `grep` (search scaffold for ContextualResolver details in JSON)
9. kast_resolve: resolve ContextualResolver using containingType parameter (SUCCESS)
10. kast_callers: find incoming callers of resolve() in ContextualResolver (SUCCESS)
11. bash: `date +%s` (record end time)

## Final answer

The callers of `ConditionalValue.ContextualResolver.resolve()` form a two-level call hierarchy:

**Root caller (direct caller of ConditionalValue.resolve):**
- `io.amichne.konditional.core.FlagDefinition.evaluateCandidate()` - private function
  - Call site: FlagDefinition.kt, line 165, column 39
  - Context: `value = candidate.resolve(...)`

**Secondary caller (caller of evaluateCandidate):**
- `matchedTrace` property in FlagDefinition
  - Call site: FlagDefinition.kt, line 127, column 21
  - Context: Result assignment in flag evaluation flow

**Symbol disambiguation note:** 
The kast_callers tool correctly disambiguated from multiple `resolve()` candidates:
- `io.amichne.konditional.rules.ConditionalValue.Resolver.resolve` (interface)
- `io.amichne.konditional.rules.ConditionalValue.StaticResolver.resolve` (implementation)
- `io.amichne.konditional.rules.ConditionalValue.ContextualResolver.resolve` (target - implementation)

The tool identified the ContextualResolver.resolve() as the specific override method being called through polymorphic dispatch.
