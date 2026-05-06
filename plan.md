## Context

The current `MetricsEngine` in `michne/kast` has 8 bespoke SQL-backed metrics that each independently query raw tables (`symbol_references`, `identifier_paths`, `file_metadata`). The metrics are noisy because:
- Dead code detection uses lexical `identifier_paths` instead of actual K2 declarations
- Impact analysis is file-level, not symbol-level
- `MetricsConfidence` only has `LOW`; there's no graduated trust signal
- Composite metrics compound noise from the raw tables

The goal is to **completely replace** the current metrics implementation with one built on four trustworthy primitives. Breaking schema changes are explicitly acceptable — bump the schema version and let `ensureSchema()` drop-and-recreate.

## New Schema (Primitive 1: Declaration Registry + Primitive 2: Symbol Edges)

### 1. Add `declarations` table to `SqliteSourceIndexStore.kt`

In `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/SqliteSourceIndexStore.kt`:

Add a new table in `createSourceIndexTables()`:

```sql
CREATE TABLE IF NOT EXISTS declarations (
    fq_id INTEGER NOT NULL,
    kind TEXT NOT NULL CHECK(kind IN ('CLASS','INTERFACE','OBJECT','FUNCTION','PROPERTY','TYPEALIAS','ENUM_CLASS','ENUM_ENTRY','CONSTRUCTOR')),
    visibility TEXT NOT NULL CHECK(visibility IN ('PUBLIC','INTERNAL','PROTECTED','PRIVATE','LOCAL')),
    prefix_id INTEGER NOT NULL,
    filename TEXT NOT NULL,
    declaration_offset INTEGER,
    module_path TEXT,
    source_set TEXT,
    PRIMARY KEY (fq_id, prefix_id, filename)
)
```

Add indexes:
```sql
CREATE INDEX IF NOT EXISTS idx_declarations_module ON declarations(module_path)
CREATE INDEX IF NOT EXISTS idx_declarations_visibility ON declarations(visibility)
CREATE INDEX IF NOT EXISTS idx_declarations_kind ON declarations(kind)
CREATE INDEX IF NOT EXISTS idx_declarations_file ON declarations(prefix_id, filename)
```

### 2. Add `edge_kind` and `source_fq_id` columns to `symbol_references`

Alter the `symbol_references` table definition to add:
```sql
CREATE TABLE IF NOT EXISTS symbol_references (
    src_prefix_id INTEGER NOT NULL,
    src_filename TEXT NOT NULL,
    source_offset INTEGER NOT NULL,
    source_fq_id INTEGER,          -- NEW: FQ name of the source symbol (nullable for backward compat during indexing)
    target_fq_id INTEGER NOT NULL,
    tgt_prefix_id INTEGER,
    tgt_filename TEXT,
    target_offset INTEGER,
    edge_kind TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK(edge_kind IN ('CALL','TYPE_REF','INHERITANCE','OVERRIDE','IMPORT','ANNOTATION','UNKNOWN')),
    PRIMARY KEY (src_prefix_id, src_filename, source_offset, target_fq_id)
)
```

### 3. Bump `SOURCE_INDEX_SCHEMA_VERSION` from 4 to 5

This ensures `ensureSchema()` will drop all tables and recreate them with the new schema. The existing `dropAllTables()` + `createAllTables()` flow handles this.

Also add `declarations` to `dropAllTables()`, `sourceIndexTablesAreCompatible()`, and `requiredTablesExist()` checks in `MetricsEngine`.

### 4. Add store methods for declarations

Add to `SqliteSourceIndexStore`:
- `replaceDeclarationsFromFile(filePath: String, declarations: List<DeclarationRow>)` — delete existing declarations for the file, insert new ones
- `replaceDeclarationsFromFiles(declarationsBySource: List<Pair<String, List<DeclarationRow>>>)` — batch version

## New Models

### 5. Update `SourceIndexModels.kt`

Add `DeclarationRow`:
```kotlin
data class DeclarationRow(
    val fqName: String,
    val kind: DeclarationKind,
    val visibility: DeclarationVisibility,
    val filePath: String,
    val declarationOffset: Int?,
    val modulePath: String?,
    val sourceSet: String?,
)

enum class DeclarationKind {
    CLASS, INTERFACE, OBJECT, FUNCTION, PROPERTY, TYPEALIAS, ENUM_CLASS, ENUM_ENTRY, CONSTRUCTOR
}

enum class DeclarationVisibility {
    PUBLIC, INTERNAL, PROTECTED, PRIVATE, LOCAL
}
```

Add `EdgeKind` enum:
```kotlin
enum class EdgeKind {
    CALL, TYPE_REF, INHERITANCE, OVERRIDE, IMPORT, ANNOTATION, UNKNOWN
}
```

Extend `SymbolReferenceRow` with optional fields:
```kotlin
data class SymbolReferenceRow(
    val sourcePath: String,
    val sourceOffset: Int,
    val sourceFqName: String? = null,   // NEW
    val targetFqName: String,
    val targetPath: String?,
    val targetOffset: Int?,
    val edgeKind: EdgeKind = EdgeKind.UNKNOWN,  // NEW
)
```

### 6. Rewrite `MetricsModels.kt`

Replace the entire file. Remove all old model classes. New models:

**Confidence envelope (Primitive 4):**
```kotlin
@Serializable
enum class ConfidenceLevel { HIGH, MEDIUM, LOW, SPECULATIVE }

@Serializable
enum class SemanticBasis { K2_RESOLVED, LEXICAL, HEURISTIC }

@Serializable
data class Confidence(
    val level: ConfidenceLevel,
    val indexCompleteness: Double,  // 0.0–1.0
    val semanticBasis: SemanticBasis,
)
```

**Primitive 1 — Declaration Registry results:**
```kotlin
@Serializable
data class DeclarationInfo(
    val fqName: String,
    val kind: String,
    val visibility: String,
    val path: String?,
    val modulePath: String?,
    val sourceSet: String?,
)

@Serializable
data class DeadCodeCandidate(
    val fqName: String,
    val kind: String,
    val visibility: String,
    val path: String?,
    val modulePath: String?,
    val sourceSet: String?,
    val confidence: Confidence,
    val reason: String,
)

@Serializable
data class ApiSurfaceMetric(
    val modulePath: String,
    val publicSymbolCount: Int,
    val internalSymbolCount: Int,
    val privateSymbolCount: Int,
    val totalSymbolCount: Int,
    val encapsulationRatio: Double,  // private / total
)
```

**Primitive 2 — Symbol Edge results:**
```kotlin
@Serializable
data class SymbolEdgeMetric(
    val sourceFqName: String?,
    val targetFqName: String,
    val edgeKind: String,
    val sourcePath: String,
    val targetPath: String?,
    val count: Int,
)

@Serializable
data class FanInMetric(
    val targetFqName: String,
    val targetPath: String?,
    val targetModulePath: String?,
    val targetSourceSet: String?,
    val occurrenceCount: Int,
    val sourceFileCount: Int,
    val sourceModuleCount: Int,
    val byEdgeKind: Map<String, Int>,  // NEW: breakdown by edge kind
    val confidence: Confidence,
)

@Serializable
data class FanOutMetric(
    val sourcePath: String,
    val sourceModulePath: String?,
    val sourceSourceSet: String?,
    val occurrenceCount: Int,
    val targetSymbolCount: Int,
    val targetFileCount: Int,
    val targetModuleCount: Int,
    val externalTargetCount: Int,
    val byEdgeKind: Map<String, Int>,  // NEW
    val confidence: Confidence,
)

@Serializable
data class ChangeImpactNode(
    val sourcePath: String,
    val depth: Int,
    val viaTargetFqName: String,
    val edgeKind: String?,
    val occurrenceCount: Int,
    val confidence: Confidence,
)
```

**Primitive 3 — Module Boundary Contract results:**
```kotlin
@Serializable
data class ModuleBoundaryMetric(
    val modulePath: String,
    val exportedSymbolCount: Int,   // public + internal declarations
    val consumedSymbolCount: Int,   // distinct cross-module targets referenced
    val publicApiReferences: Int,   // cross-module refs hitting PUBLIC targets
    val internalLeakReferences: Int, // cross-module refs hitting INTERNAL targets
    val confidence: Confidence,
)

@Serializable
data class ModuleCouplingMetric(
    val sourceModulePath: String,
    val sourceSourceSet: String?,
    val targetModulePath: String,
    val targetSourceSet: String?,
    val referenceCount: Int,
    val publicApiCount: Int,        // NEW: refs to PUBLIC symbols
    val internalLeakCount: Int,     // NEW: refs to INTERNAL symbols
    val byEdgeKind: Map<String, Int>, // NEW
    val confidence: Confidence,
)

@Serializable
data class ModuleCycleMetric(
    val cycle: List<String>,
    val totalReferenceCount: Int,
    val weakestEdgeSource: String,
    val weakestEdgeTarget: String,
    val weakestEdgeReferenceCount: Int,
    val confidence: Confidence,
)

@Serializable
data class ModuleDepthMetric(
    val modulePath: String,
    val fileCount: Int,
    val declaredSymbolCount: Int,
    val internalRefCount: Int,
    val externalRefCount: Int,
    val cohesionRatio: Double,
    val refsPerFile: Double,
    val diagnosis: ModuleDepthDiagnosis,
    val confidence: Confidence,
)
```

Keep `LowUsageSymbol` but add confidence. Keep graph models (`MetricsGraph`, `MetricsGraphNode`, `MetricsGraphEdge`, `MetricsGraphIndex`, `MetricsGraphNodeType`, `MetricsGraphEdgeType`). Keep `ModuleDepthDiagnosis`. Remove `MetricsConfidence` and `ImpactSemantics` enums.

### 7. Rewrite `MetricsEngine.kt`

Replace the entire implementation. The new structure should be:

**Private infrastructure** (keep/adapt):
- `readMetric()`, `readMetricRows()`, `MetricQuerySpec`, `MetricResultRow`, `connection()`, `schemaIsCurrent()`, `requiredTablesExist()` — adapt to include `declarations` in required tables
- `searchSymbols()`, `popularSymbols()`, `directSymbolMatches()`, `fuzzySymbolMatches()` — keep as-is (these are fine)

**Confidence computation** (new private method):
```kotlin
private fun currentConfidence(conn: Connection): Confidence {
    // Check: do declarations exist? (K2_RESOLVED vs LEXICAL)
    // Check: what fraction of manifest files have been Phase-2 indexed?
    // Return appropriate Confidence
}
```

Logic:
- If `declarations` table has rows → `semanticBasis = K2_RESOLVED`
- If `declarations` table is empty but `identifier_paths` has rows → `semanticBasis = LEXICAL`
- `indexCompleteness` = count of files in `symbol_references` (distinct src files) / count of files in `file_manifest`
- `level` = HIGH if K2_RESOLVED + completeness > 0.95, MEDIUM if K2_RESOLVED + completeness > 0.5, LOW if LEXICAL, SPECULATIVE otherwise

**Primitive query methods** (new public methods):

1. `declarations(filter)` → `List<DeclarationInfo>` — SELECT from `declarations` JOIN `fq_names`
2. `symbolEdges(fqName, edgeKinds, depth)` → `List<SymbolEdgeMetric>` — SELECT from `symbol_references` with edge_kind filter, using source_fq_id for symbol-level traversal
3. `moduleBoundary(modulePath)` → `ModuleBoundaryMetric` — JOIN `declarations` with cross-module `symbol_references` to classify public API vs internal leak
4. `apiSurface(modulePath)` → `ApiSurfaceMetric` — aggregate declarations by visibility per module

**Derived metric methods** (rewritten public methods, composed from primitives):

1. `fanInRanking(limit, filter)` → same signature, but query now includes `edge_kind` breakdown and `Confidence`
2. `fanOutRanking(limit, filter)` → same, with edge_kind breakdown and Confidence
3. `moduleCouplingMatrix()` → same, but now distinguishes public API refs from internal leaks by joining `declarations`
4. `lowUsageSymbols(maxOccurrences, limit, filter)` → same, with Confidence
5. `moduleCycles()` → same, with Confidence
6. `moduleDepthMetrics()` → same, with Confidence; use `declarations` table instead of `identifier_paths` for `declaredSymbolCount`
7. `deadCodeCandidates(filter)` → **rewrite**: query `declarations` LEFT JOIN `symbol_references` WHERE no inbound refs exist. Confidence is HIGH when declarations come from K2, not lexical. Private symbols with zero refs = HIGH confidence dead code. Public symbols with zero refs = MEDIUM (could be used externally).
8. `changeImpactRadius(fqName, depth, filter)` → rewrite CTE to walk symbol-to-symbol via `source_fq_id` when available, falling back to file-level when not. Include `edge_kind` in results.
9. `graph(fqName, depth)` → adapt to use new impact data; keep the graph structure models
10. `searchSymbols(query, limit)` → keep as-is

### 8. Update `ReferenceIndexer.kt`

Extend to accept and write declarations alongside references:

```kotlin
fun indexReferences(
    filePaths: Collection<String>,
    referenceScanner: (String) -> List<SymbolReferenceRow>,
    declarationScanner: ((String) -> List<DeclarationRow>)? = null,  // NEW, nullable for backward compat
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
)
```

In the batch loop, if `declarationScanner` is provided, also call it per file and write declarations via `store.replaceDeclarationsFromFile()`.

### 9. Update `BackgroundIndexer.kt`

In `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/BackgroundIndexer.kt`:

Update `startPhase2()` signature to accept an optional `declarationScanner`:
```kotlin
fun startPhase2(
    changedPaths: Set<String>? = null,
    referenceScanner: (String) -> List<SymbolReferenceRow>,
    declarationScanner: ((String) -> List<DeclarationRow>)? = null,
)
```

Pass `declarationScanner` through to `ReferenceIndexer.indexReferences()`.

Similarly update `reindexFiles()` to accept and forward `declarationScanner`.

**Note**: The actual K2 backend code that calls `startPhase2()` will need to provide a `declarationScanner` lambda that extracts declarations from the K2 analysis session. The `referenceScanner` lambda should also be enriched to populate `edgeKind` and `sourceFqName` on `SymbolReferenceRow`. These changes are in the K2 backend layer (likely in the IntelliJ plugin or standalone backend code that creates the scanner lambdas). The indexer infrastructure just needs to accept and persist the richer data.

### 10. Update CLI integration

**`kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperExecutor.kt`**:

Update `executeMetrics()` to handle new metric types. The `WrapperMetric` enum (in `analysis-api/.../WrapperContracts.kt`) needs new variants:
- Add: `API_SURFACE`, `MODULE_BOUNDARY`, `DECLARATIONS`
- Keep: `FAN_IN`, `FAN_OUT`, `COUPLING`, `LOW_USAGE`, `CYCLES`, `MODULE_DEPTH`, `DEAD_CODE`, `IMPACT`

Update the `when` block in `executeMetrics()` to call the new/updated `MetricsEngine` methods.

**`kast-cli/src/main/kotlin/io/github/amichne/kast/cli/skill/MetricsResultEncoder.kt`**:

Update all encoder functions to handle the new model shapes (added `confidence`, `byEdgeKind`, etc. fields). Add new encoders for `API_SURFACE`, `MODULE_BOUNDARY`, `DECLARATIONS`.

**`analysis-api/src/main/kotlin/io/github/amichne/kast/api/wrapper/WrapperContracts.kt`**:

Add new `WrapperMetric` enum entries.

### 11. Rewrite tests

**`index-store/src/test/kotlin/io/github/amichne/kast/indexstore/MetricsEngineTest.kt`**:

Rewrite all tests. The `seededWorkspace()` helper needs to also insert `declarations` rows. Key test scenarios:

- Dead code with HIGH confidence: declare a PRIVATE symbol in `declarations`, no inbound refs → HIGH confidence dead code
- Dead code with MEDIUM confidence: declare a PUBLIC symbol, no inbound refs → MEDIUM confidence (could be used externally)
- Fan-in with edge_kind breakdown
- Module coupling distinguishing public API vs internal leak
- Impact radius using symbol-level edges when `source_fq_id` is populated
- Confidence envelope reflects index completeness
- API surface metrics per module
- Module boundary metrics
- All existing test scenarios (cycles, depth, graph, search) adapted to new models

Also update `seededWorkspace()` to use the new `SymbolReferenceRow` fields and insert `DeclarationRow`s via the store.

Update `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/skill/SkillWrapperSerializerTest.kt` and `SkillWrapperRequestCasingTest.kt` for new `WrapperMetric` variants.

### 12. Important implementation notes

- This is a **breaking schema change**. Bumping `SOURCE_INDEX_SCHEMA_VERSION` to 5 means all existing `source-index.db` files will be dropped and recreated on next startup. This is intentional and acceptable per the user's request.
- The `declarations` table will initially be empty until the K2 backend scanner lambdas are updated to provide declaration data. The `Confidence` envelope handles this gracefully: when `declarations` is empty, confidence degrades to `LEXICAL`/`LOW`.
- The `edge_kind` column defaults to `'UNKNOWN'` so existing reference scanner code continues to work without changes. Enriching the scanner to provide real edge kinds is a separate follow-up.
- The `source_fq_id` column is nullable so existing scanners that don't resolve the source symbol still work. Impact analysis falls back to file-level traversal when `source_fq_id` is NULL.
- Keep `searchSymbols()` and `graph()` working — they are used by `MetricsGraphPicker` and are orthogonal to the primitive redesign.
