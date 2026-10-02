package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.HostedServiceObservation
import io.github.amichne.kast.distribution.contract.HostedCompatibilityStatusFailure
import io.github.amichne.kast.distribution.contract.HostedCompatibilityStatusField
import io.github.amichne.kast.distribution.contract.HostedCompatibilityStatusSyntax
import io.github.amichne.kast.distribution.contract.HostedServiceStatus
import io.github.amichne.kast.distribution.contract.HostedServiceUnavailableFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityField
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityMismatch
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilitySyntaxFailure

/** Project admitted identities and finite failures without using provenance as compatibility evidence. */
internal fun projectHostedService(observation: HostedServiceObservation): HostedServiceStatus =
    when (observation) {
        is HostedServiceObservation.Compatible ->
            HostedServiceStatus.Compatible(
                observation.root.path.toString(),
                observation.descriptor.host.toString(),
                observation.descriptor.hostPid,
                observation.compatibility.provenance.version.value,
            )
        is HostedServiceObservation.Incompatible ->
            HostedServiceStatus.Incompatible(
                observation.root.path.toString(),
                observation.descriptor.host.toString(),
                observation.descriptor.hostPid,
                observation.provenance.version.value,
                projectCompatibilityFailure(observation.compatibilityFailure),
            )
        is HostedServiceObservation.Unavailable ->
            HostedServiceStatus.Unavailable(observation.root.path.toString(), projectHostFailure(observation.failure))
    }

private fun projectHostFailure(failure: ExistingIdeFailure): HostedServiceUnavailableFailure =
    when (failure) {
        ExistingIdeFailure.CONFIGURATION_REJECTED -> HostedServiceUnavailableFailure.CONFIGURATION_REJECTED
        ExistingIdeFailure.INVALID_NAME -> HostedServiceUnavailableFailure.INVALID_NAME
        ExistingIdeFailure.INVALID_REQUEST -> HostedServiceUnavailableFailure.INVALID_REQUEST
        ExistingIdeFailure.HOST_UNAVAILABLE -> HostedServiceUnavailableFailure.HOST_UNAVAILABLE
        ExistingIdeFailure.DESCRIPTOR_REJECTED -> HostedServiceUnavailableFailure.DESCRIPTOR_REJECTED
        ExistingIdeFailure.RESPONSE_REJECTED -> HostedServiceUnavailableFailure.RESPONSE_REJECTED
        ExistingIdeFailure.REQUEST_TOO_LARGE -> HostedServiceUnavailableFailure.REQUEST_TOO_LARGE
        ExistingIdeFailure.DEADLINE_EXCEEDED -> HostedServiceUnavailableFailure.DEADLINE_EXCEEDED
        ExistingIdeFailure.TRANSPORT_REJECTED -> HostedServiceUnavailableFailure.TRANSPORT_REJECTED
        ExistingIdeFailure.SCHEMA_UNAVAILABLE -> HostedServiceUnavailableFailure.SCHEMA_UNAVAILABLE
        ExistingIdeFailure.OPERATION_UNSUPPORTED -> HostedServiceUnavailableFailure.OPERATION_UNSUPPORTED
        ExistingIdeFailure.APPROVAL_REQUIRED -> HostedServiceUnavailableFailure.APPROVAL_REQUIRED
        ExistingIdeFailure.APPROVAL_REJECTED -> HostedServiceUnavailableFailure.APPROVAL_REJECTED
        ExistingIdeFailure.COMPATIBILITY_REJECTED -> HostedServiceUnavailableFailure.COMPATIBILITY_REJECTED
    }

internal fun projectCompatibilityFailure(failure: IdeHostCompatibilityFailure): HostedCompatibilityStatusFailure =
    when (failure) {
        is IdeHostCompatibilityFailure.Malformed ->
            HostedCompatibilityStatusFailure.Malformed(
                projectCompatibilityField(failure.field),
                when (failure.syntax) {
                    IdeHostCompatibilitySyntaxFailure.BLANK -> HostedCompatibilityStatusSyntax.BLANK
                    IdeHostCompatibilitySyntaxFailure.TOO_LONG -> HostedCompatibilityStatusSyntax.TOO_LONG
                    IdeHostCompatibilitySyntaxFailure.INVALID_FORMAT -> HostedCompatibilityStatusSyntax.INVALID_FORMAT
                },
            )
        is IdeHostCompatibilityFailure.Mismatch -> projectMismatch(failure.mismatch)
        is IdeHostCompatibilityFailure.UnknownCapability ->
            HostedCompatibilityStatusFailure.UnknownCapability(failure.operationId.value)
        is IdeHostCompatibilityFailure.UnsupportedCapability ->
            HostedCompatibilityStatusFailure.UnsupportedCapability(failure.operation.id.value)
        is IdeHostCompatibilityFailure.DuplicateCapability ->
            HostedCompatibilityStatusFailure.DuplicateCapability(failure.capability.operation.id.value)
    }

private fun projectCompatibilityField(field: IdeHostCompatibilityField): HostedCompatibilityStatusField =
    when (field) {
        IdeHostCompatibilityField.IDE_BUILD -> HostedCompatibilityStatusField.IDE_BUILD
        IdeHostCompatibilityField.KOTLIN_PLUGIN_BUILD -> HostedCompatibilityStatusField.KOTLIN_PLUGIN_BUILD
        IdeHostCompatibilityField.KAST_PLUGIN_VERSION -> HostedCompatibilityStatusField.KAST_PLUGIN_VERSION
        IdeHostCompatibilityField.RUNTIME_PROTOCOL_IDENTITY -> HostedCompatibilityStatusField.RUNTIME_PROTOCOL_IDENTITY
        IdeHostCompatibilityField.OPERATION_REGISTRY_DIGEST -> HostedCompatibilityStatusField.OPERATION_REGISTRY_DIGEST
        IdeHostCompatibilityField.WIRE_SCHEMA_DIGEST -> HostedCompatibilityStatusField.WIRE_SCHEMA_DIGEST
        IdeHostCompatibilityField.CAPABILITIES -> HostedCompatibilityStatusField.CAPABILITIES
    }

private fun projectMismatch(mismatch: IdeHostCompatibilityMismatch): HostedCompatibilityStatusFailure.Mismatch {
    val (expected, observed) =
        when (mismatch) {
            is IdeHostCompatibilityMismatch.IdeBuild ->
                listOf(mismatch.expected.value) to listOf(mismatch.observed.value)
            is IdeHostCompatibilityMismatch.KotlinPluginBuild ->
                listOf(mismatch.expected.value) to listOf(mismatch.observed.value)
            is IdeHostCompatibilityMismatch.KastPluginVersion ->
                listOf(mismatch.expected.value) to listOf(mismatch.observed.value)
            is IdeHostCompatibilityMismatch.RuntimeProtocol ->
                listOf(mismatch.expected.value) to listOf(mismatch.observed.value)
            is IdeHostCompatibilityMismatch.OperationRegistry ->
                listOf(mismatch.expected.value) to listOf(mismatch.observed.value)
            is IdeHostCompatibilityMismatch.WireSchema ->
                listOf(mismatch.expected.value) to listOf(mismatch.observed.value)
            is IdeHostCompatibilityMismatch.Capabilities ->
                mismatch.expected.capabilities.map { it.operation.id.value } to
                    mismatch.observed.capabilities.map { it.operation.id.value }
        }
    return HostedCompatibilityStatusFailure.Mismatch(projectCompatibilityField(mismatch.field), expected, observed)
}
