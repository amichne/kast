## Prompt

Enumerate all Kotlin source modules in this workspace. For each module, list its name, source roots, and module type (main, test, etc.). The repo uses Gradle.

## Tool calls

1. `kast_workspace_files` with `includeFiles: true`, `maxFilesPerModule: 50`, `workspaceRoot: /tmp/kast-vp-rebench/konditional-with`

## Final answer

Found 8 Kotlin source modules in the workspace:

### 1. :konditional-engine[main]
- **Module Type**: main
- **Source Roots**:
  - `/private/tmp/kast-vp-rebench/konditional-with/konditional-engine/src/main/kotlin`

### 2. :konditional-engine[testFixtures]
- **Module Type**: testFixtures
- **Source Roots**:
  - `/private/tmp/kast-vp-rebench/konditional-with/konditional-engine/src/testFixtures/kotlin`

### 3. :konditional-engine[test]
- **Module Type**: test
- **Source Roots**:
  - `/private/tmp/kast-vp-rebench/konditional-with/konditional-engine/src/test/kotlin`

### 4. :konditional-json[main]
- **Module Type**: main
- **Source Roots**:
  - `/private/tmp/kast-vp-rebench/konditional-with/konditional-json/src/main/kotlin`

### 5. :konditional-json[test]
- **Module Type**: test
- **Source Roots**:
  - `/private/tmp/kast-vp-rebench/konditional-with/konditional-json/src/test/kotlin`

### 6. :konditional-types[main]
- **Module Type**: main
- **Source Roots**:
  - `/private/tmp/kast-vp-rebench/konditional-with/konditional-types/src/main/kotlin`

### 7. :konditional-types[test]
- **Module Type**: test
- **Source Roots**:
  - `/private/tmp/kast-vp-rebench/konditional-with/konditional-types/src/test/kotlin`

### 8. :smoke-test[test]
- **Module Type**: test
- **Source Roots**:
  - `/private/tmp/kast-vp-rebench/konditional-with/smoke-test/src/test/kotlin`

**Summary**: The workspace contains 4 Gradle sub-projects (konditional-engine, konditional-json, konditional-types, smoke-test) with a total of 8 modules across main, test, and testFixtures source sets.
