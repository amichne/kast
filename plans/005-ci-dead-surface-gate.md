## Objective

Add build-time and CI-time checks that guarantee no defunct surface can leak back into the codebase. This is the lock
that prevents accidental re-introduction of deleted patterns.

## Repository: michne/kast

## Files to create/modify

### 1. New file: `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/tty/VisibleSurfaceAuditTest.kt`

A test that asserts the exact set of visible CLI commands:

```kotlin
@Test
fun `visible commands match v1 surface`() {
    val allowed = setOf(
        "up", "status", "stop", "rpc",
        "workspace status", "workspace ensure", "workspace stop",
        "capabilities",
        "completion bash", "completion zsh",
        "install", "install skill", "install copilot-extension",
        "self status", "self doctor", "self uninstall", "self upgrade",
        "verify-extension", "smoke",
        "daemon start", "config init",
        "eval skill",
        "gradle run",
        "metrics fan-in", "metrics fan-out", "metrics coupling",
        "metrics low-usage", "metrics cycles", "metrics module-depth",
        "metrics dead-code", "metrics impact", "metrics graph",
    )
    val actual = CliCommandCatalog.visibleCommands().map { it.commandText }.toSet()
    assertEquals(allowed, actual, "Visible CLI surface has drifted from v1 spec")
}
```

Also add a negative assertion:

```kotlin
@Test
fun `no defunct analysis commands are visible`() {
    val banned = setOf(
        "resolve", "references", "call-hierarchy", "type-hierarchy",
        "insertion-point", "diagnostics", "outline", "workspace-symbol",
        "workspace-search", "implementations", "code-actions", "completions",
        "rename", "optimize-imports", "apply-edits",
        "workspace files", "workspace refresh",
    )
    val visible = CliCommandCatalog.visibleCommands().map { it.commandText }.toSet()
    val violations = visible.intersect(banned)
    assertTrue(violations.isEmpty(), "Defunct commands found in visible surface: $violations")
}
```

### 2. New file: `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/tty/NoDefunctImportsTest.kt`

A test that scans main source files for banned import patterns:

```kotlin
@Test
fun `no main sources import deleted wrapper types`() {
    val mainDir = Path.of("kast-cli/src/main/kotlin")
    val banned = listOf(
        "io.github.amichne.kast.api.wrapper",
        "SkillWrapperName",
        "SkillWrapperExecutor",
        "SkillWrapperSerializer",
        "SkillWrapperInput",
        "NamedSymbolResolver",
        "SkillLogFile",
        "MetricsResultEncoder",
        "WrapperOpenApiDocument",
    )
    val violations = mutableListOf<String>()
    Files.walk(mainDir)
        .filter { it.toString().endsWith(".kt") }
        .forEach { file ->
            val content = file.toFile().readText()
            banned.forEach { pattern ->
                if (pattern in content) {
                    violations.add("${file.fileName}: contains '$pattern'")
                }
            }
        }
    assertTrue(violations.isEmpty(), "Defunct imports found:\n${violations.joinToString("\n")}")
}
```

### 3. New file: `.github/scripts/audit-cli-surface.sh`

A shell script for CI that checks for banned patterns across the entire repo:

```bash
#!/usr/bin/env bash
set -euo pipefail

BANNED="SkillWrapperName|SkillWrapperExecutor|SkillWrapperSerializer|parseSkillCommand|parseDirectSkillWrapper|callKastSkill"

echo "Checking for defunct CLI surface patterns..."
if grep -rE "$BANNED" \
    kast-cli/src/main/ \
    .github/extensions/ \
    .agents/skills/kast/SKILL.md \
    AGENTS.md 2>/dev/null; then
  echo "FATAL: defunct surface detected in source or docs"
  exit 1
fi

echo "Checking extension.mjs uses callKast, not callKastSkill..."
if grep -q "callKastSkill" .github/extensions/kast/extension.mjs; then
  echo "FATAL: extension.mjs still uses callKastSkill"
  exit 1
fi

echo "Checking no wrapper-openapi.yaml in skill references..."
if [ -f ".agents/skills/kast/references/wrapper-openapi.yaml" ]; then
  echo "FATAL: wrapper-openapi.yaml still exists in skill references"
  exit 1
fi

echo "CLI surface audit passed."
```

### 4. CI integration

Add the audit script to the existing CI workflow (likely `.github/workflows/` — find the main CI workflow file and add a
step):

```yaml
- name: Audit CLI surface
  run: bash .github/scripts/audit-cli-surface.sh
```

### 5. `.github/extensions/kast/extension.mjs` — tool handler audit

Add a comment block at the top of the tools array:

```js
// SURFACE AUDIT: All tool handlers MUST use callKast(method, params).
// Do NOT add callKastSkill — it was removed in the v1 surface cleanup.
```

## Verification gate

- `./gradlew :kast-cli:test` passes including the new audit tests
- `.github/scripts/audit-cli-surface.sh` exits 0
- Manually add a test: temporarily re-introduce a `SkillWrapperName` import in a main source file → verify the audit
  test fails → revert
- Manually add a test: temporarily add a visible command called "resolve" to the catalog → verify the surface audit test
  fails → revert
