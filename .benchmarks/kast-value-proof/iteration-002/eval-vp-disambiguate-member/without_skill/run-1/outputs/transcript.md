## Prompt
Find all usages of the key property on Feature, not every key in the repo. List each call site with file path and line number.

## Tool calls
1. bash: date +%s (record start time)
2. bash: find /tmp/kast-vp-rebench/konditional-without -type f -name "*.kt" | head -20
3. bash: grep -r "class Feature" /tmp/kast-vp-rebench/konditional-without --include="*.kt"
4. bash: grep -r "data class Feature" /tmp/kast-vp-rebench/konditional-without --include="*.kt"
5. bash: find /tmp/kast-vp-rebench/konditional-without -name "*Feature*.kt" -type f
6. view: /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/features/Feature.kt
7. bash: grep -r "\.key" /tmp/kast-vp-rebench/konditional-without --include="*.kt" | grep -i feature
8. bash: grep -rn "\.key" /tmp/kast-vp-rebench/konditional-without --include="*.kt" | grep -E "(feature|Feature)"
9. view: /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt (lines 55-70)
10. view: /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt (lines 150-165)
11. view: /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/internal/builders/FlagBuilder.kt (lines 150-165)
12. bash: find /tmp/kast-vp-rebench/konditional-without -name "FlagBuilder.kt" -type f
13. bash: grep -rn "\bfeature\.key\b" /tmp/kast-vp-rebench/konditional-without --include="*.kt"
14. bash: grep -rn "\.key\b" /tmp/kast-vp-rebench/konditional-without --include="*.kt" | grep -E "\b(key|it|f|feature)\.(key|id)"
15. bash: grep -rn "{ .*\.key" /tmp/kast-vp-rebench/konditional-without --include="*.kt"
16. bash: grep -rn "\.key" /tmp/kast-vp-rebench/konditional-without/konditional-engine --include="*.kt" | grep -v ".keys" | grep -v ".keySet" | grep -v "keyName" | grep -v "keyId"
17. bash: grep -rn "\.key" /tmp/kast-vp-rebench/konditional-without --include="*.kt" | grep -v ".keys" | grep -v ".keySet" | grep -v "keyName" | grep -v "keyId" | grep "Feature\|feature\|key\."
18. view: /tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt (lines 56-62)
19. bash: date +%s (record end time)

## Final answer
All usages of the key property on Feature:

1. `/tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/Namespace.kt:61` - `key.key` where `key` parameter is of type `Feature<T, C, M>`

2. `/tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/core/FlagDefinition.kt:158` - `feature.key`

3. `/tmp/kast-vp-rebench/konditional-without/konditional-engine/src/main/kotlin/io/amichne/konditional/internal/builders/FlagBuilder.kt:158` - `feature.key`
