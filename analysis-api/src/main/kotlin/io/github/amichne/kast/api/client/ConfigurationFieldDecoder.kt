package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.client.fields.*
import com.sksamuel.hoplite.BooleanNode
import com.sksamuel.hoplite.ConfigFailure
import com.sksamuel.hoplite.ConfigResult
import com.sksamuel.hoplite.DecoderContext
import com.sksamuel.hoplite.DoubleNode
import com.sksamuel.hoplite.LongNode
import com.sksamuel.hoplite.MapNode
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.StringNode
import com.sksamuel.hoplite.decoder.NullHandlingDecoder
import com.sksamuel.hoplite.fp.invalid
import com.sksamuel.hoplite.fp.valid
import kotlin.reflect.KClass
import kotlin.reflect.KType

class ConfigurationFieldDecoder : NullHandlingDecoder<ConfigurationField<*>> {
    override fun supports(type: KType): Boolean =
        (type.classifier as? KClass<*>) in supportedFieldTypes

    override fun safeDecode(
        node: Node,
        type: KType,
        context: DecoderContext,
    ): ConfigResult<ConfigurationField<*>> {
        val leafNode = if (node is MapNode) node.value else node
        return when (type.classifier as? KClass<*>) {
            CacheEnabled::class -> decodeBoolean(leafNode, type).mapField(::CacheEnabled)
            CacheSourceIndexSaveDelayMillis::class -> decodeLong(leafNode, type).mapField(::CacheSourceIndexSaveDelayMillis)
            CacheWriteDelayMillis::class -> decodeLong(leafNode, type).mapField(::CacheWriteDelayMillis)
            CliBinaryPath::class -> decodeString(leafNode, type).mapField(::CliBinaryPath)
            GradleMaxIncludedProjects::class -> decodeInt(leafNode, type).mapField(::GradleMaxIncludedProjects)
            GradleToolingApiTimeoutMillis::class -> decodeLong(leafNode, type).mapField(::GradleToolingApiTimeoutMillis)
            IndexingIdentifierIndexWaitMillis::class -> decodeLong(leafNode, type).mapField(::IndexingIdentifierIndexWaitMillis)
            IndexingPhase2BatchSize::class -> decodeInt(leafNode, type).mapField(::IndexingPhase2BatchSize)
            IndexingPhase2Enabled::class -> decodeBoolean(leafNode, type).mapField(::IndexingPhase2Enabled)
            IndexingPhase2Parallelism::class -> decodeInt(leafNode, type).mapField(::IndexingPhase2Parallelism)
            IndexingReferenceBatchSize::class -> decodeInt(leafNode, type).mapField(::IndexingReferenceBatchSize)
            IndexingRemoteEnabled::class -> decodeBoolean(leafNode, type).mapField(::IndexingRemoteEnabled)
            IndexingRemoteSourceIndexUrl::class -> decodeOptionalConfigString(leafNode, type).mapField(::IndexingRemoteSourceIndexUrl)
            IntellijBackendEnabled::class -> decodeBoolean(leafNode, type).mapField(::IntellijBackendEnabled)
            PathsBinDir::class -> decodeString(leafNode, type).mapField(::PathsBinDir)
            PathsCacheDir::class -> decodeString(leafNode, type).mapField(::PathsCacheDir)
            PathsDescriptorDir::class -> decodeString(leafNode, type).mapField(::PathsDescriptorDir)
            PathsInstallRoot::class -> decodeString(leafNode, type).mapField(::PathsInstallRoot)
            PathsLibDir::class -> decodeString(leafNode, type).mapField(::PathsLibDir)
            PathsLogsDir::class -> decodeString(leafNode, type).mapField(::PathsLogsDir)
            PathsSocketDir::class -> decodeString(leafNode, type).mapField(::PathsSocketDir)
            ProfilingDurationSeconds::class -> decodeLong(leafNode, type).mapField(::ProfilingDurationSeconds)
            ProfilingEmitManifest::class -> decodeBoolean(leafNode, type).mapField(::ProfilingEmitManifest)
            ProfilingEnabled::class -> decodeBoolean(leafNode, type).mapField(::ProfilingEnabled)
            ProfilingModes::class -> decodeString(leafNode, type).mapField(::ProfilingModes)
            ProfilingOtlpEndpoint::class -> decodeOptionalConfigString(leafNode, type).mapField(::ProfilingOtlpEndpoint)
            ProfilingOutputDir::class -> decodeString(leafNode, type).mapField(::ProfilingOutputDir)
            ServerMaxConcurrentRequests::class -> decodeInt(leafNode, type).mapField(::ServerMaxConcurrentRequests)
            ServerMaxResults::class -> decodeInt(leafNode, type).mapField(::ServerMaxResults)
            ServerRequestTimeoutMillis::class -> decodeLong(leafNode, type).mapField(::ServerRequestTimeoutMillis)
            StandaloneBackendEnabled::class -> decodeBoolean(leafNode, type).mapField(::StandaloneBackendEnabled)
            StandaloneRuntimeLibsDir::class -> decodeOptionalConfigString(leafNode, type).mapField(::StandaloneRuntimeLibsDir)
            TelemetryDetail::class -> decodeString(leafNode, type).mapField(::TelemetryDetail)
            TelemetryEnabled::class -> decodeBoolean(leafNode, type).mapField(::TelemetryEnabled)
            TelemetryOutputFile::class -> decodeOptionalConfigString(leafNode, type).mapField(::TelemetryOutputFile)
            TelemetryScopes::class -> decodeString(leafNode, type).mapField(::TelemetryScopes)
            WatcherDebounceMillis::class -> decodeLong(leafNode, type).mapField(::WatcherDebounceMillis)
            else -> ConfigFailure.NoSuchDecoder(type, emptyList()).invalid()
        }
    }

    private fun decodeOptionalConfigString(
        node: Node,
        type: KType,
    ): ConfigResult<OptionalConfigString> = decodeString(node, type).map { value ->
        OptionalConfigString(value)
    }

    private fun decodeString(
        node: Node,
        type: KType,
    ): ConfigResult<String> = when (node) {
        is StringNode -> node.value.valid()
        is BooleanNode -> node.value.toString().valid()
        is LongNode -> node.value.toString().valid()
        is DoubleNode -> node.value.toString().valid()
        else -> ConfigFailure.DecodeError(node, type).invalid()
    }

    private fun decodeBoolean(
        node: Node,
        type: KType,
    ): ConfigResult<Boolean> = when (node) {
        is BooleanNode -> node.value.valid()
        is StringNode -> when (node.value.lowercase()) {
            "true", "t", "1", "yes" -> true.valid()
            "false", "f", "0", "no" -> false.valid()
            else -> ConfigFailure.DecodeError(node, type).invalid()
        }
        else -> ConfigFailure.DecodeError(node, type).invalid()
    }

    private fun decodeLong(
        node: Node,
        type: KType,
    ): ConfigResult<Long> = when (node) {
        is LongNode -> node.value.valid()
        is StringNode -> node.value.toLongOrNull()?.valid() ?: ConfigFailure.DecodeError(node, type).invalid()
        else -> ConfigFailure.DecodeError(node, type).invalid()
    }

    private fun decodeInt(
        node: Node,
        type: KType,
    ): ConfigResult<Int> = when (node) {
        is LongNode -> node.value.toInt().takeIf { it.toLong() == node.value }?.valid()
            ?: ConfigFailure.DecodeError(node, type).invalid()
        is DoubleNode -> node.value.toInt().valid()
        is StringNode -> node.value.toIntOrNull()?.valid() ?: ConfigFailure.DecodeError(node, type).invalid()
        else -> ConfigFailure.DecodeError(node, type).invalid()
    }

    private fun <T> ConfigResult<T>.mapField(
        factory: (T) -> ConfigurationField<*>,
    ): ConfigResult<ConfigurationField<*>> = map(factory)

    private companion object {
        val supportedFieldTypes: Set<KClass<*>> = setOf(
            CacheEnabled::class,
            CacheSourceIndexSaveDelayMillis::class,
            CacheWriteDelayMillis::class,
            CliBinaryPath::class,
            GradleMaxIncludedProjects::class,
            GradleToolingApiTimeoutMillis::class,
            IndexingIdentifierIndexWaitMillis::class,
            IndexingPhase2BatchSize::class,
            IndexingPhase2Enabled::class,
            IndexingPhase2Parallelism::class,
            IndexingReferenceBatchSize::class,
            IndexingRemoteEnabled::class,
            IndexingRemoteSourceIndexUrl::class,
            IntellijBackendEnabled::class,
            PathsBinDir::class,
            PathsCacheDir::class,
            PathsDescriptorDir::class,
            PathsInstallRoot::class,
            PathsLibDir::class,
            PathsLogsDir::class,
            PathsSocketDir::class,
            ProfilingDurationSeconds::class,
            ProfilingEmitManifest::class,
            ProfilingEnabled::class,
            ProfilingModes::class,
            ProfilingOtlpEndpoint::class,
            ProfilingOutputDir::class,
            ServerMaxConcurrentRequests::class,
            ServerMaxResults::class,
            ServerRequestTimeoutMillis::class,
            StandaloneBackendEnabled::class,
            StandaloneRuntimeLibsDir::class,
            TelemetryDetail::class,
            TelemetryEnabled::class,
            TelemetryOutputFile::class,
            TelemetryScopes::class,
            WatcherDebounceMillis::class,
        )
    }
}
