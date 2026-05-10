## Overview

Add a first-class profiling configuration surface to kast that enables async-profiler-based CPU/allocation/lock/wall profiling, OTLP telemetry export, and structured manifest emission for agent consumption. This follows the established ConfigurationField pattern using `@JvmInline value class` for zero-allocation overhead.

## Phase 1: Config model and field classes (analysis-api)

### 1.1 Create profiling field classes as value classes
**File**: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ProfilingEnabled.kt`
```kotlin
package io.github.amichne.kast.api.client.fields

@JvmInline
value class ProfilingEnabled(override val value: Boolean) : ConfigurationField<Boolean>() {
    override val section: String get() = "profiling"
    override val key: String get() = "enabled"
    override val default: ConfigurationDefault<Boolean> get() = ConfigurationDefault(false)
}
```

**File**: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ProfilingModes.kt`
```kotlin
package io.github.amichne.kast.api.client.fields

@JvmInline
value class ProfilingModes(override val value: String) : ConfigurationField<String>() {
    override val section: String get() = "profiling"
    override val key: String get() = "modes"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault("cpu")
    
    fun parseModes(): Set<ProfilingMode> {
        return value.split(",").mapNotNull { ProfilingMode.parse(it.trim()) }.toSet()
    }
}

enum class ProfilingMode(val aliases: List<String>) {
    CPU(listOf("cpu")),
    ALLOCATION(listOf("alloc", "allocation")),
    LOCK(listOf("lock")),
    WALL(listOf("wall"));
    
    companion object {
        fun parse(value: String): ProfilingMode? = entries.find { value.lowercase() in it.aliases }
    }
}
```

**File**: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ProfilingDurationSeconds.kt`
```kotlin
package io.github.amichne.kast.api.client.fields

@JvmInline
value class ProfilingDurationSeconds(override val value: Long) : ConfigurationField<Long>() {
    override val section: String get() = "profiling"
    override val key: String get() = "durationSeconds"
    override val default: ConfigurationDefault<Long> get() = ConfigurationDefault(30L)
}
```

**File**: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ProfilingOutputDir.kt`
```kotlin
package io.github.amichne.kast.api.client.fields

@JvmInline
value class ProfilingOutputDir(override val value: String) : ConfigurationField<String>() {
    override val section: String get() = "profiling"
    override val key: String get() = "outputDir"
    override val default: ConfigurationDefault<String> get() = ConfigurationDefault("{logsDir}/profiling")
}
```

**File**: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ProfilingOtlpEndpoint.kt`
```kotlin
package io.github.amichne.kast.api.client.fields

@JvmInline
value class ProfilingOtlpEndpoint(override val value: OptionalConfigString) : ConfigurationField<OptionalConfigString>() {
    override val section: String get() = "profiling"
    override val key: String get() = "otlpEndpoint"
    override val default: ConfigurationDefault<OptionalConfigString> get() = ConfigurationDefault(OptionalConfigString.Unset)
}
```

**File**: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ProfilingEmitManifest.kt`
```kotlin
package io.github.amichne.kast.api.client.fields

@JvmInline
value class ProfilingEmitManifest(override val value: Boolean) : ConfigurationField<Boolean>() {
    override val section: String get() = "profiling"
    override val key: String get() = "emitManifest"
    override val default: ConfigurationDefault<Boolean> get() = ConfigurationDefault(true)
}
```

### 1.2 Add ProfilingConfig and ProfilingConfigOverride to KastConfig.kt
**File**: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/KastConfig.kt`

Add to the `KastConfig` data class (line 10-20):
```kotlin
data class KastConfig(
    val server: ServerConfig,
    val indexing: IndexingConfig,
    val cache: CacheConfig,
    val watcher: WatcherConfig,
    val gradle: GradleConfig,
    val telemetry: TelemetryConfig,
    val profiling: ProfilingConfig,  // NEW
    val backends: BackendsConfig,
    val paths: PathsConfig,
    val cli: CliConfig,
)
```

Add the ProfilingConfig data class (after TelemetryConfig, around line 156):
```kotlin
data class ProfilingConfig(
    val enabled: ProfilingEnabled,
    val modes: ProfilingModes,
    val durationSeconds: ProfilingDurationSeconds,
    val outputDir: ProfilingOutputDir,
    val otlpEndpoint: ProfilingOtlpEndpoint,
    val emitManifest: ProfilingEmitManifest,
)
```

Add ProfilingConfigOverride data class (after TelemetryConfigOverride, around line 238):
```kotlin
data class ProfilingConfigOverride(
    val enabled: ProfilingEnabled? = null,
    val modes: ProfilingModes? = null,
    val durationSeconds: ProfilingDurationSeconds? = null,
    val outputDir: ProfilingOutputDir? = null,
    val otlpEndpoint: ProfilingOtlpEndpoint? = null,
    val emitManifest: ProfilingEmitManifest? = null,
)
```

Add to `KastConfigOverride` data class (line 186-196):
```kotlin
data class KastConfigOverride(
    val server: ServerConfigOverride? = null,
    val indexing: IndexingConfigOverride? = null,
    val cache: CacheConfigOverride? = null,
    val watcher: WatcherConfigOverride? = null,
    val gradle: GradleConfigOverride? = null,
    val telemetry: TelemetryConfigOverride? = null,
    val profiling: ProfilingConfigOverride? = null,  // NEW
    val backends: BackendsConfigOverride? = null,
    val paths: PathsConfigOverride? = null,
    val cli: CliConfigOverride? = null,
)
```

Add to `KastConfig.defaults()` (line 38-82):
```kotlin
profiling = ProfilingConfig(
    enabled = ProfilingEnabled(false),
    modes = ProfilingModes("cpu"),
    durationSeconds = ProfilingDurationSeconds(30L),
    outputDir = ProfilingOutputDir("{logsDir}/profiling"),
    otlpEndpoint = ProfilingOtlpEndpoint(OptionalConfigString.Unset),
    emitManifest = ProfilingEmitManifest(true),
),
```

Add merge function (after TelemetryConfig.merge, around line 323):
```kotlin
private fun ProfilingConfig.merge(override: ProfilingConfigOverride?): ProfilingConfig = copy(
    enabled = override?.enabled ?: enabled,
    modes = override?.modes ?: modes,
    durationSeconds = override?.durationSeconds ?: durationSeconds,
    outputDir = override?.outputDir ?: outputDir,
    otlpEndpoint = override?.otlpEndpoint ?: otlpEndpoint,
    emitManifest = override?.emitManifest ?: emitManifest,
)
```

Add to `KastConfig.merge()` (line 268-281):
```kotlin
private fun KastConfig.merge(override: KastConfigOverride): KastConfig {
    val mergedPaths = paths.merge(override.paths)
    return copy(
        server = server.merge(override.server),
        indexing = indexing.merge(override.indexing),
        cache = cache.merge(override.cache),
        watcher = watcher.merge(override.watcher),
        gradle = gradle.merge(override.gradle),
        telemetry = telemetry.merge(override.telemetry),
        profiling = profiling.merge(override.profiling),  // NEW
        backends = backends.merge(override.backends, mergedPaths),
        paths = mergedPaths,
        cli = cli.merge(override.cli, mergedPaths),
    )
}
```

### 1.3 Register fields in ConfigurationField.defaultFields()
**File**: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/fields/ConfigurationField.kt`

Add to the list in `defaultFields()` (after TelemetryOutputFile, around line 30):
```kotlin
ProfilingEnabled(false),
ProfilingModes("cpu"),
ProfilingDurationSeconds(30L),
ProfilingOutputDir("{logsDir}/profiling"),
ProfilingOtlpEndpoint(OptionalConfigString.Unset),
ProfilingEmitManifest(true),
```

### 1.4 Update KastConfigTest
**File**: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/KastConfigTest.kt`

Add to the expected fields set in `configuration field section key pairs are unique and complete` test (line 80-119):
```kotlin
"profiling" to "enabled",
"profiling" to "modes",
"profiling" to "durationSeconds",
"profiling" to "outputDir",
"profiling" to "otlpEndpoint",
"profiling" to "emitManifest",
```

## Phase 2: CLI flag parsing (kast-cli)

### 2.1 Add --profile flag to CliCommandParser
**File**: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliCommandParser.kt`

In the `daemonStartOptions()` method (around line 658-671), add profiling flag extraction:
```kotlin
fun daemonStartOptions(): DaemonStartOptions {
    val runtimeLibsDir = options["runtime-libs-dir"]
        ?.takeIf(String::isNotBlank)
        ?.let { Path.of(it).toAbsolutePath().normalize() }
    
    // Extract profiling flags
    val profileEnabled = flags.contains("profile")
    val profileModes = options["profile-modes"]
    val profileDuration = options["profile-duration"]?.toLongOrNull()
    val profileOtlpEndpoint = options["profile-otlp-endpoint"]
    
    val profilingOverride = if (profileEnabled || profileModes != null || profileDuration != null || profileOtlpEndpoint != null) {
        ProfilingConfigOverride(
            enabled = if (profileEnabled) ProfilingEnabled(true) else null,
            modes = profileModes?.let { ProfilingModes(it) },
            durationSeconds = profileDuration?.let { ProfilingDurationSeconds(it) },
            otlpEndpoint = profileOtlpEndpoint?.let { ProfilingOtlpEndpoint(OptionalConfigString.Set(it)) },
        )
    } else null
    
    val forwardedArgs = (options - listOf("runtime-libs-dir", "profile-modes", "profile-duration", "profile-otlp-endpoint"))
        .map { (key, value) -> "--$key=$value" }
    
    return DaemonStartOptions(
        standaloneArgs = forwardedArgs,
        profilingOverride = profilingOverride,  // NEW field in DaemonStartOptions
        workspaceRoot = options["workspace-root"]
                            ?.takeIf(String::isNotBlank)
                            ?.let { Path.of(it).toAbsolutePath().normalize() }
                        ?: Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize(),
        runtimeLibsDir = runtimeLibsDir,
    )
}
```

### 2.2 Update DaemonStartOptions data class
**File**: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/options/DaemonStartOptions.kt`

Add the profiling override field:
```kotlin
data class DaemonStartOptions(
    val standaloneArgs: List<String>,
    val profilingOverride: ProfilingConfigOverride? = null,  // NEW
    val workspaceRoot: Path,
    val runtimeLibsDir: Path? = null,
)
```

### 2.3 Add profiling flags to CliCommandCatalog
**File**: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliCommandCatalog.kt`

Add to the daemon start command metadata:
```kotlin
CliCommandMetadata(
    path = listOf("daemon", "start"),
    flags = listOf("profile"),
    options = listOf(
        "runtime-libs-dir",
        "workspace-root",
        "profile-modes",
        "profile-duration",
        "profile-otlp-endpoint",
    ),
    // ... existing fields
)
```

### 2.4 Update config template
**File**: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliService.kt`

Add to `defaultConfigTemplate()` (after telemetry section, around line 442-480):
```kotlin
# [profiling]
# enabled = false
# modes = "cpu"
# durationSeconds = 30
# outputDir = "{logsDir}/profiling"
# otlpEndpoint = null
# emitManifest = true
```

## Phase 3: Runtime profiling conversion (backend-standalone)

### 3.1 Create ProfilingConfig runtime data class
**File**: `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/profiling/ProfilingConfig.kt`
```kotlin
package io.github.amichne.kast.standalone.profiling

import io.github.amichne.kast.api.client.fields.ProfilingMode
import java.nio.file.Path

data class ProfilingConfig(
    val enabled: Boolean,
    val modes: Set<ProfilingMode>,
    val durationSeconds: Long,
    val outputDir: Path,
    val otlpEndpoint: String?,
    val emitManifest: Boolean,
) {
    companion object {
        fun fromConfig(
            config: io.github.amichne.kast.api.client.KastConfig,
            logsDir: Path,
        ): ProfilingConfig {
            val profiling = config.profiling
            val resolvedOutputDir = profiling.outputDir.value
                .replace("{logsDir}", logsDir.toString())
                .let { Path.of(it) }
            
            return ProfilingConfig(
                enabled = profiling.enabled.value,
                modes = profiling.modes.parseModes(),
                durationSeconds = profiling.durationSeconds.value,
                outputDir = resolvedOutputDir,
                otlpEndpoint = profiling.otlpEndpoint.value.orNull(),
                emitManifest = profiling.emitManifest.value,
            )
        }
    }
}
```

### 3.2 Create ProfilingManager
**File**: `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/profiling/ProfilingManager.kt`
```kotlin
package io.github.amichne.kast.standalone.profiling

import io.github.amichne.kast.api.client.fields.ProfilingMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class ProfilingManifest(
    val version: String = "1",
    val timestamp: String,
    val pid: Long,
    val modes: List<String>,
    val durationSeconds: Long,
    val artifacts: Map<String, ArtifactMetadata>,
) {
    @Serializable
    data class ArtifactMetadata(
        val type: String,
        val path: String,
        val sizeBytes: Long?,
    )
}

internal class ProfilingManager(
    private val config: ProfilingConfig,
    private val pid: Long = ProcessHandle.current().pid(),
) {
    private val active = AtomicBoolean(false)
    private val json = Json { prettyPrint = true }
    
    fun startProfiling(): ProfilingResult? {
        if (!config.enabled || !active.compareAndSet(false, true)) {
            return null
        }
        
        Files.createDirectories(config.outputDir)
        val timestamp = Instant.now().toString()
        val artifacts = mutableMapOf<String, ProfilingManifest.ArtifactMetadata>()
        
        try {
            // Run async-profiler for each mode
            config.modes.forEach { mode ->
                val artifactPath = runAsyncProfiler(mode, timestamp)
                if (artifactPath != null) {
                    val sizeBytes = Files.sizeIfExists(artifactPath)
                    artifacts[mode.name.lowercase()] = ProfilingManifest.ArtifactMetadata(
                        type = when (mode) {
                            ProfilingMode.CPU -> "flamegraph-html"
                            ProfilingMode.ALLOCATION -> "flamegraph-html"
                            ProfilingMode.LOCK -> "flamegraph-html"
                            ProfilingMode.WALL -> "flamegraph-html"
                        },
                        path = artifactPath.toString(),
                        sizeBytes = sizeBytes,
                    )
                }
            }
            
            // Emit manifest if requested
            if (config.emitManifest) {
                val manifest = ProfilingManifest(
                    timestamp = timestamp,
                    pid = pid,
                    modes = config.modes.map { it.name.lowercase() },
                    durationSeconds = config.durationSeconds,
                    artifacts = artifacts,
                )
                val manifestPath = config.outputDir.resolve("profiling-manifest.json")
                Files.writeString(manifestPath, json.encodeToString(manifest))
            }
            
            return ProfilingResult(
                manifestPath = if (config.emitManifest) config.outputDir.resolve("profiling-manifest.json") else null,
                artifacts = artifacts.mapValues { it.value.path }.mapKeys { Path.of(it.value) },
            )
        } finally {
            active.set(false)
        }
    }
    
    private fun runAsyncProfiler(mode: ProfilingMode, timestamp: String): Path? {
        val event = when (mode) {
            ProfilingMode.CPU -> "cpu"
            ProfilingMode.ALLOCATION -> "alloc"
            ProfilingMode.LOCK -> "lock"
            ProfilingMode.WALL -> "wall"
        }
        val outputFile = config.outputDir.resolve("profiling-${mode.name.lowercase()}-$timestamp.html")
        
        return try {
            val process = ProcessBuilder(
                "asprof",
                "-d", config.durationSeconds.toString(),
                "-e", event,
                "-f", outputFile.toString(),
                pid.toString(),
            ).start()
            
            if (process.waitFor() == 0) {
                outputFile
            } else {
                null
            }
        } catch (e: Exception) {
            // async-profiler not available or failed
            null
        }
    }
    
    private fun Files.sizeIfExists(path: Path): Long? = 
        try { size(path) } catch (e: Exception) { null }
}

data class ProfilingResult(
    val manifestPath: Path?,
    val artifacts: Map<Path, String>,
)
```

### 3.3 Wire ProfilingManager into StandaloneRuntime
**File**: `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/StandaloneRuntime.kt`

Modify `start()` method to create ProfilingManager:
```kotlin
internal fun start(options: StandaloneServerOptions): RunningStandaloneRuntime {
    System.setProperty("java.awt.headless", "true")
    val config = KastConfig.load(options.workspaceRoot, overrides = options.profilingOverride)
    val phasedDiscoveryResult = discoverStandaloneWorkspaceLayoutPhased(
        workspaceRoot = options.workspaceRoot,
        sourceRoots = options.sourceRoots,
        classpathRoots = options.classpathRoots,
        moduleName = options.moduleName,
        config = config,
    )
    val session = StandaloneAnalysisSession(
        workspaceRoot = options.workspaceRoot,
        sourceRoots = options.sourceRoots,
        classpathRoots = options.classpathRoots,
        moduleName = options.moduleName,
        phasedDiscoveryResult = phasedDiscoveryResult,
        config = config,
    )
    val telemetry = StandaloneTelemetry.fromConfig(options.workspaceRoot, config)
    val profilingConfig = ProfilingConfig.fromConfig(config, config.paths.logsDir.toPath())
    val profilingManager = ProfilingManager(profilingConfig)
    
    // Start profiling if enabled
    val profilingResult = profilingManager.startProfiling()
    
    val backend = StandaloneAnalysisBackend(
        workspaceRoot = options.workspaceRoot,
        limits = ServerLimits(
            maxResults = options.maxResults,
            requestTimeoutMillis = options.requestTimeoutMillis,
            maxConcurrentRequests = options.maxConcurrentRequests,
        ),
        session = session,
        telemetry = telemetry,
        profilingManager = profilingManager,  // NEW
    )
    // ... rest of method
}
```

### 3.4 Update StandaloneServerOptions to accept profiling override
**File**: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/StandaloneServerOptions.kt`

Add profiling override field:
```kotlin
data class StandaloneServerOptions(
    val workspaceRoot: Path,
    val sourceRoots: List<Path> = emptyList(),
    val classpathRoots: List<Path> = emptyList(),
    val moduleName: String? = null,
    val maxResults: Int = 500,
    val requestTimeoutMillis: Long = 30_000L,
    val maxConcurrentRequests: Int = 4,
    val transport: Transport = Transport.UNIX_SOCKET,
    val profilingOverride: ProfilingConfigOverride? = null,  // NEW
)
```

### 3.5 Update daemon start to pass profiling override
**File**: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliService.kt`

In the daemon start command handler, pass profiling override to StandaloneServerOptions.

## Phase 4: OTLP export for telemetry

### 4.1 Add OTLP exporter to StandaloneTelemetry
**File**: `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/telemetry/StandaloneTelemetry.kt`

Add OTLP dependency to `backend-standalone/build.gradle.kts`:
```kotlin
implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.38.0")
```

Modify `create()` method to support OTLP:
```kotlin
private fun create(config: StandaloneTelemetryConfig): StandaloneTelemetry {
    val exporters = mutableListOf<SpanExporter>()
    
    // Always add JSONL exporter
    exporters.add(JsonLineSpanExporter(config.outputFile, config.detail))
    
    // Add OTLP exporter if endpoint is configured
    val otlpEndpoint = System.getenv("KAST_OTLP_ENDPOINT") 
        ?: System.getenv("profiling.otlpEndpoint")  // Also check from config
    if (otlpEndpoint != null) {
        try {
            val otlpExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .build()
            exporters.add(otlpExporter)
        } catch (e: Exception) {
            // Log warning but continue with JSONL only
        }
    }
    
    val spanProcessor = SimpleSpanProcessor.create(
        SpanProcessor.composite(exporters)
    )
    // ... rest of method
}
```

## Phase 5: Add thread naming to BackgroundIndexer phase2

**File**: `backend-standalone/src/main/kotlin/io/github/amichne/kast/standalone/BackgroundIndexer.kt`

The phase1 thread is already named (line 77). Add naming to phase2 thread (around line 120-130):
```kotlin
phase2Thread = thread(
    start = true,
    isDaemon = true,
    name = "kast-background-indexer-phase2",
) {
    // ... phase2 logic
}
```

## Phase 6: Create analyze-spans.py script

**File**: `scripts/analyze-spans.py`
```python
#!/usr/bin/env python3
import json
import sys
from pathlib import Path
from collections import defaultdict
import statistics

def analyze_spans(jsonl_path: Path):
    spans = []
    with open(jsonl_path) as f:
        for line in f:
            if line.strip():
                spans.append(json.loads(line))
    
    # Latency report by span name
    durations_by_name = defaultdict(list)
    for span in spans:
        name = span.get('name', 'unknown')
        duration_ms = span.get('durationNanos', 0) / 1_000_000
        durations_by_name[name].append(duration_ms)
    
    print("=== Latency Report (p95 ms) ===")
    for name, durations in sorted(durations_by_name.items(), key=lambda x: statistics.quantile(x[1], 0.95), reverse=True):
        p95 = statistics.quantile(durations, 0.95)
        print(f"{name}: {p95:.2f}")
    
    # Lock contention report
    lock_spans = [s for s in spans if 'lock' in s.get('name', '').lower()]
    if lock_spans:
        print("\n=== Lock Contention ===")
        for span in lock_spans:
            attrs = span.get('attributes', {})
            print(f"{span['name']}: type={attrs.get('kast.lock.type')}, duration={span.get('durationNanos', 0)/1_000_000:.2f}ms")
    
    # I/O report
    io_spans = [s for s in spans if 'io' in s.get('name', '').lower()]
    if io_spans:
        print("\n=== I/O Operations ===")
        for span in io_spans:
            attrs = span.get('attributes', {})
            print(f"{span['name']}: file={attrs.get('kast.io.filePath')}, bytes={attrs.get('kast.io.bytesRead')}, duration={span.get('durationNanos', 0)/1_000_000:.2f}ms")

if __name__ == '__main__':
    analyze_spans(Path(sys.argv[1]))
```

## Phase 7: Update tests

### 7.1 Add profiling config test
**File**: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/KastConfigTest.kt`

Add test for profiling config loading and override merging.

### 7.2 Add CLI parser test
**File**: `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/tty/CliCommandParserTest.kt`

Add test for --profile flag parsing and DaemonStartOptions profiling override.

### 7.3 Add ProfilingManager test
**File**: `backend-standalone/src/test/kotlin/io/github/amichne/kast/standalone/profiling/ProfilingManagerTest.kt`

Add unit tests for ProfilingManager manifest generation and async-profiler invocation (mocked).

## Implementation order

1. Phase 1 (config model) - all field classes as `@JvmInline value class`, KastConfig changes, test updates
2. Phase 2 (CLI parsing) - flags, DaemonStartOptions, config template
3. Phase 3 (runtime conversion) - ProfilingConfig, ProfilingManager, StandaloneRuntime wiring
4. Phase 4 (OTLP export) - StandaloneTelemetry changes
5. Phase 5 (thread naming) - BackgroundIndexer phase2 naming
6. Phase 6 (analysis script) - analyze-spans.py
7. Phase 7 (tests) - all test additions

## Notes

- All profiling field classes use `@JvmInline value class` for zero-allocation overhead since `section`, `key`, and `default` are constants for all instances
- All profiling is gated behind `profiling.enabled = false` by default, so zero overhead when disabled
- async-profiler is invoked as an external process; if not available, profiling fails gracefully with null artifacts
- The manifest format is stable JSON for agent consumption
- OTLP export is opt-in via environment variable or config
- Thread naming makes profiles interpretable in flame graphs
