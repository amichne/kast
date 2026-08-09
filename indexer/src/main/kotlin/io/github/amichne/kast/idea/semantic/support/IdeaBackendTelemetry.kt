package io.github.amichne.kast.idea
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.workspaceDataDirectory
import io.github.amichne.kast.server.*
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.*
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.nio.file.Path
import java.time.Instant
internal enum class IdeaTelemetryScope {
    RENAME, PLAN_REPLACEMENT, PLAN_ADD_FILE, PLAN_ADD_DECLARATION,
    VERIFY_MUTATION_POSTCONDITION, EXACT_FILE_OBSERVATION, EXACT_FILE_IMAGE_CAS,
    REFERENCES, CALL_HIERARCHY, TYPE_HIERARCHY, IMPLEMENTATIONS, COMPLETIONS,
    SEMANTIC_INSERTION_POINT, DIAGNOSTICS, OPTIMIZE_IMPORTS, RESOLVE,
    WORKSPACE_FILES, WORKSPACE_SYMBOL_SEARCH, WORKSPACE_SEARCH, READ_ACTION,
    FILE_OUTLINE, APPLY_EDITS, REFRESH,
    ;
    companion object {
        fun parse(rawValue: String): IdeaTelemetryScope? = when (rawValue.trim().lowercase()) {
            "rename" -> RENAME
            "plan-replacement", "plan_replacement", "planreplacement" -> PLAN_REPLACEMENT
            "plan-add-file", "plan_add_file", "planaddfile" -> PLAN_ADD_FILE
            "plan-add-declaration", "plan_add_declaration", "planadddeclaration" -> PLAN_ADD_DECLARATION
            "verify-mutation-postcondition", "verify_mutation_postcondition", "verifymutationpostcondition" ->
                VERIFY_MUTATION_POSTCONDITION
            "exact-file-observation", "exact_file_observation", "exactfileobservation" -> EXACT_FILE_OBSERVATION
            "exact-file-image-cas", "exact_file_image_cas", "exactfileimagecas" -> EXACT_FILE_IMAGE_CAS
            "references", "find-references", "find_references" -> REFERENCES
            "call-hierarchy", "call_hierarchy", "callhierarchy" -> CALL_HIERARCHY
            "type-hierarchy", "type_hierarchy", "typehierarchy" -> TYPE_HIERARCHY
            "implementations" -> IMPLEMENTATIONS
            "completions" -> COMPLETIONS
            "semantic-insertion-point", "semantic_insertion_point", "semanticinsertionpoint" -> SEMANTIC_INSERTION_POINT
            "diagnostics" -> DIAGNOSTICS
            "optimize-imports", "optimize_imports", "optimizeimports" -> OPTIMIZE_IMPORTS
            "resolve", "symbol-resolve", "symbol_resolve" -> RESOLVE
            "workspace-files", "workspace_files", "workspacefiles" -> WORKSPACE_FILES
            "workspace-symbol-search", "workspace_symbol_search", "workspacesymbolsearch" -> WORKSPACE_SYMBOL_SEARCH
            "workspace-search", "workspace_search", "workspacesearch" -> WORKSPACE_SEARCH
            "read-action", "read_action", "readaction" -> READ_ACTION
            "file-outline", "file_outline", "fileoutline", "outline" -> FILE_OUTLINE
            "apply-edits", "apply_edits", "applyedits" -> APPLY_EDITS
            "refresh" -> REFRESH
            else -> null
        }
    }
}
internal enum class IdeaTelemetryDetail { BASIC, VERBOSE;
    companion object {
        fun parse(rawValue: String?): IdeaTelemetryDetail =
            if (rawValue?.trim()?.equals("verbose", ignoreCase = true) == true) VERBOSE else BASIC
    }
}
internal data class IdeaTelemetryConfig(
    val enabled: Boolean, val scopes: Set<IdeaTelemetryScope>, val detail: IdeaTelemetryDetail, val outputFile: Path,
)
internal class IdeaTelemetrySpan internal constructor(
    private val telemetry: IdeaBackendTelemetry,
    private val scope: IdeaTelemetryScope,
    private val span: Span?,
) {
    fun setAttribute(key: String, value: Any?) {
        if (span == null || value == null) return
        setSpanAttribute(span, key, value)
    }

    fun addEvent(
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
        verboseOnly: Boolean = false,
    ) {
        if (span == null || (verboseOnly && !telemetry.isVerbose(scope))) return
        span.addEvent(name, buildAttributes(attributes))
    }

    inline fun <T> child(
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
        verboseOnly: Boolean = false,
        block: (IdeaTelemetrySpan) -> T,
    ): T = telemetry.inSpan(
        scope = scope,
        name = name,
        attributes = attributes,
        verboseOnly = verboseOnly,
        block = block,
    )

    companion object {
        fun disabled(telemetry: IdeaBackendTelemetry, scope: IdeaTelemetryScope): IdeaTelemetrySpan =
            IdeaTelemetrySpan(telemetry = telemetry, scope = scope, span = null)
    }
}

internal class IdeaBackendTelemetry private constructor(
    private val config: IdeaTelemetryConfig?,
    private val tracer: Tracer?,
) {
    fun isEnabled(scope: IdeaTelemetryScope): Boolean = config != null && scope in config.scopes

    fun isVerbose(scope: IdeaTelemetryScope): Boolean = isEnabled(scope) && config?.detail == IdeaTelemetryDetail.VERBOSE

    inline fun <T> inSpan(
        scope: IdeaTelemetryScope,
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
        verboseOnly: Boolean = false,
        block: (IdeaTelemetrySpan) -> T,
    ): T {
        if (!isEnabled(scope) || (verboseOnly && !isVerbose(scope))) {
            return block(IdeaTelemetrySpan.disabled(this, scope))
        }

        return when (val traceState = RpcTraceContext.current()) {
            RpcTraceState.Absent -> runSpan(
                scope = scope,
                name = name,
                attributes = attributes,
                block = block,
            )

            is RpcTraceState.Active -> if (Span.current().spanContext.isValid) {
                runSpan(
                    scope = scope,
                    name = name,
                    attributes = attributes + correlatedPhaseAttributes(
                        traceState.correlation,
                        name.substringAfterLast('.'),
                    ),
                    block = block,
                )
            } else {
                runCorrelatedOperation(
                    scope = scope,
                    name = name,
                    attributes = attributes,
                    correlation = traceState.correlation,
                    block = block,
                )
            }
        }
    }

    fun recordReadAction(scope: IdeaTelemetryScope, name: String, waitNanos: Long, holdNanos: Long) {
        if (!isEnabled(IdeaTelemetryScope.READ_ACTION)) return
        val endedAt = Instant.now()
        val startedAt = endedAt.minusNanos(waitNanos.coerceAtLeast(0L).saturatingAdd(holdNanos.coerceAtLeast(0L)))
        val correlationAttributes = when (val traceState = RpcTraceContext.current()) {
            RpcTraceState.Absent -> emptyMap()
            is RpcTraceState.Active -> correlatedPhaseAttributes(traceState.correlation, "readAction")
        }
        recordCompletedPhase(
            scope = scope,
            name = name,
            phaseName = "readAction",
            startedAt = startedAt,
            endedAt = endedAt,
            attributes = mapOf(
                "kast.readAction.waitNanos" to waitNanos,
                "kast.readAction.holdNanos" to holdNanos,
            ) + correlationAttributes,
            outcome = CorrelatedSpanOutcome.SUCCEEDED,
        )
    }

    private inline fun <T> runCorrelatedOperation(
        scope: IdeaTelemetryScope,
        name: String,
        attributes: Map<String, Any?>,
        correlation: RpcTraceCorrelation,
        block: (IdeaTelemetrySpan) -> T,
    ): T {
        val remoteParent = Span.wrap(
            SpanContext.createFromRemoteParent(
                correlation.traceIdAtTelemetryBoundary(),
                correlation.parentSpanIdAtTelemetryBoundary(),
                TraceFlags.getSampled(),
                TraceState.getDefault(),
            ),
        )
        val transportSpan = checkNotNull(tracer)
            .spanBuilder(RPC_TRANSPORT_SPAN_NAME)
            .setParent(Context.root().with(remoteParent))
            .startSpan()
        applySpanAttributes(
            transportSpan,
            correlatedAttributes(correlation, CorrelatedTraceRole.TRANSPORT),
        )
        val transportScope = transportSpan.makeCurrent()
        return try {
            runSpan(
                scope = scope,
                name = name,
                attributes = attributes + correlatedAttributes(
                    correlation,
                    CorrelatedTraceRole.BACKEND_OPERATION,
                ),
            ) { operationSpan ->
                val bodyStartedAt = Instant.now()
                val result = try {
                    block(operationSpan)
                } catch (failure: Throwable) {
                    recordCompletedPhase(
                        scope = scope,
                        name = "$name.operationBody",
                        phaseName = "operationBody",
                        startedAt = bodyStartedAt,
                        endedAt = Instant.now(),
                        attributes = correlatedPhaseAttributes(correlation, "operationBody"),
                        outcome = CorrelatedSpanOutcome.FAILED,
                    )
                    throw failure
                }
                recordCompletedPhase(
                    scope, "$name.operationBody", "operationBody", bodyStartedAt, Instant.now(),
                    correlatedPhaseAttributes(correlation, "operationBody"), CorrelatedSpanOutcome.SUCCEEDED,
                )
                result
            }
        } catch (failure: Throwable) {
            transportSpan.setStatus(StatusCode.ERROR)
            throw failure
        } finally {
            transportScope.close()
            transportSpan.end()
        }
    }

    private inline fun <T> runSpan(
        scope: IdeaTelemetryScope,
        name: String,
        attributes: Map<String, Any?>,
        block: (IdeaTelemetrySpan) -> T,
    ): T {
        val startedSpan = checkNotNull(tracer).spanBuilder(name).startSpan()
        applySpanAttributes(startedSpan, attributes)
        val otelScope = startedSpan.makeCurrent()
        val telemetrySpan = IdeaTelemetrySpan(this, scope, startedSpan)
        return try {
            block(telemetrySpan)
        } catch (failure: Throwable) {
            startedSpan.setStatus(StatusCode.ERROR)
            throw failure
        } finally {
            otelScope.close()
            startedSpan.end()
        }
    }

    private fun recordCompletedPhase(
        scope: IdeaTelemetryScope,
        name: String,
        phaseName: String,
        startedAt: Instant,
        endedAt: Instant,
        attributes: Map<String, Any?>,
        outcome: CorrelatedSpanOutcome,
    ) {
        if (!isEnabled(scope)) return
        val phaseSpan = checkNotNull(tracer)
            .spanBuilder(name)
            .setStartTimestamp(startedAt)
            .startSpan()
        applySpanAttributes(phaseSpan, attributes + mapOf("kast.phase.name" to phaseName))
        if (outcome == CorrelatedSpanOutcome.FAILED) phaseSpan.setStatus(StatusCode.ERROR)
        phaseSpan.end(endedAt)
    }

    companion object {
        fun disabled(): IdeaBackendTelemetry = IdeaBackendTelemetry(
            config = null,
            tracer = null,
        )

        fun create(config: IdeaTelemetryConfig): IdeaBackendTelemetry {
            if (!config.enabled || config.scopes.isEmpty()) {
                return disabled()
            }

            val exporter = IdeaJsonLineSpanExporter(
                outputFile = config.outputFile,
                detail = config.detail,
            )
            val tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
            val openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build()

            return IdeaBackendTelemetry(
                config = config,
                tracer = openTelemetry.getTracer("io.github.amichne.kast.idea"),
            )
        }

        fun fromConfig(
            workspaceRoot: Path,
            config: KastConfig = KastConfig.load(workspaceRoot),
        ): IdeaBackendTelemetry {
            if (!config.telemetry.enabled.value) {
                return disabled()
            }

            val scopes = if (config.telemetry.scopes.value.equals("all", ignoreCase = true)) {
                IdeaTelemetryScope.entries.toSet()
            } else {
                parseScopes(config.telemetry.scopes.value) ?: IdeaTelemetryScope.entries.toSet()
            }
            val detail = IdeaTelemetryDetail.parse(config.telemetry.detail.value)
            val outputFile = resolveOutputFile(
                rawValue = config.telemetry.outputFile.value.orNull,
                workspaceRoot = workspaceRoot,
            )

            return create(
                IdeaTelemetryConfig(
                    enabled = true,
                    scopes = scopes,
                    detail = detail,
                    outputFile = outputFile,
                ),
            )
        }

        private fun parseScopes(rawValue: String?): Set<IdeaTelemetryScope>? {
            if (rawValue.isNullOrBlank()) return null
            val scopes = rawValue.split(',')
                .mapNotNull(IdeaTelemetryScope::parse)
                .toSet()
            return scopes.ifEmpty { null }
        }

        private fun resolveOutputFile(rawValue: String?, workspaceRoot: Path): Path {
            val configuredPath = rawValue
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.let { path -> if (path.isAbsolute) path else workspaceRoot.resolve(path) }

            return (configuredPath ?: workspaceDataDirectory(workspaceRoot).resolve("telemetry/idea-spans.jsonl"))
                .toAbsolutePath()
                .normalize()
        }
    }
}

@PublishedApi internal enum class CorrelatedTraceRole(val attributeValue: String) {
    TRANSPORT("TRANSPORT"), BACKEND_OPERATION("BACKEND_OPERATION"), PHASE("PHASE") }
internal enum class CorrelatedSpanOutcome { SUCCEEDED, FAILED }

@PublishedApi internal fun correlatedAttributes(
    correlation: RpcTraceCorrelation,
    role: CorrelatedTraceRole,
): Map<String, Any?> = buildMap {
    put("kast.invocation.id", correlation.invocationIdAtTelemetryBoundary())
    put("kast.invocation.parentId", correlation.parentInvocationIdAtTelemetryBoundary())
    put("kast.request.id", correlation.requestIdAtTelemetryBoundary())
    put("kast.trace.role", role.attributeValue)
}

private fun correlatedPhaseAttributes(correlation: RpcTraceCorrelation, phaseName: String): Map<String, Any?> =
    correlatedAttributes(correlation, CorrelatedTraceRole.PHASE) + mapOf("kast.phase.name" to phaseName)

private fun Long.saturatingAdd(other: Long): Long =
    if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

private fun applySpanAttributes(span: Span, attributes: Map<String, Any?>) {
    attributes.forEach { (key, value) ->
        if (value != null) setSpanAttribute(span, key, value)
    }
}

private fun buildAttributes(attributes: Map<String, Any?>): Attributes {
    val builder = Attributes.builder()
    attributes.forEach { (key, value) ->
        when (value) {
            null -> Unit
            is Boolean -> builder.put(AttributeKey.booleanKey(key), value)
            is Double -> builder.put(AttributeKey.doubleKey(key), value)
            is Int -> builder.put(AttributeKey.longKey(key), value.toLong())
            is Long -> builder.put(AttributeKey.longKey(key), value)
            else -> builder.put(AttributeKey.stringKey(key), value.toString())
        }
    }
    return builder.build()
}

private fun setSpanAttribute(span: Span, key: String, value: Any) {
    when (value) {
        is Boolean -> span.setAttribute(AttributeKey.booleanKey(key), value)
        is Double -> span.setAttribute(AttributeKey.doubleKey(key), value)
        is Int -> span.setAttribute(AttributeKey.longKey(key), value.toLong())
        is Long -> span.setAttribute(AttributeKey.longKey(key), value)
        else -> span.setAttribute(AttributeKey.stringKey(key), value.toString())
    }
}
