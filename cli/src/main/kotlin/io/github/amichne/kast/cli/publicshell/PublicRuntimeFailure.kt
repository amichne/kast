package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.DaemonOperationClientFailure
import io.github.amichne.kast.appserver.DaemonOperationClientRejection
import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.DaemonOperationProtocolFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.protocol.contract.HostedEndpointFailure
import kotlinx.serialization.Serializable

/** Public-only classification preserves the private adapter's established exit property and encoded document. */
internal fun classifyPublicRuntimeFailure(failure: DaemonOperationClientRejection): PublicShellExit =
    when (failure) {
        is DaemonOperationClientRejection.Command,
        is DaemonOperationClientRejection.Coordinator -> PublicShellExit.BOOTSTRAP
        is DaemonOperationClientRejection.Transport ->
            when (failure.failure) {
                DaemonOperationClientFailure.CODE_SOURCE_UNAVAILABLE,
                DaemonOperationClientFailure.CODE_SOURCE_INVALID,
                DaemonOperationClientFailure.LIBRARY_DIRECTORY_INVALID,
                DaemonOperationClientFailure.PRODUCT_ROOT_UNAVAILABLE,
                DaemonOperationClientFailure.RESOURCE_DIRECTORY_UNAVAILABLE,
                DaemonOperationClientFailure.KAST_EXECUTABLE_UNAVAILABLE -> PublicShellExit.RUNTIME
                DaemonOperationClientFailure.UNAVAILABLE,
                DaemonOperationClientFailure.OUTCOME_UNOBSERVED -> PublicShellExit.IO
                DaemonOperationClientFailure.RESPONSE_REJECTED -> PublicShellExit.PROTOCOL
            }
        is DaemonOperationClientRejection.Server -> classifyServerFailure(failure.failure)
    }

private fun classifyServerFailure(cause: DaemonOperationFailure): PublicShellExit =
    when (cause) {
        is DaemonOperationFailure.Root -> PublicShellExit.WORKSPACE
        is DaemonOperationFailure.Input -> PublicShellExit.INPUT
        is DaemonOperationFailure.Preparation,
        is DaemonOperationFailure.Workspace -> PublicShellExit.RUNTIME
        is DaemonOperationFailure.Protocol ->
            when (cause.reason) {
                DaemonOperationProtocolFailure.INVALID_REQUEST -> PublicShellExit.INPUT
                DaemonOperationProtocolFailure.UNSUPPORTED_VERSION,
                DaemonOperationProtocolFailure.IDENTITY_REJECTED,
                DaemonOperationProtocolFailure.OPERATION_UNSUPPORTED,
                DaemonOperationProtocolFailure.RESPONSE_REJECTED -> PublicShellExit.PROTOCOL
                DaemonOperationProtocolFailure.LIFECYCLE_TRANSITION,
                DaemonOperationProtocolFailure.CAPACITY_EXCEEDED -> PublicShellExit.RUNTIME
                DaemonOperationProtocolFailure.UNAVAILABLE,
                DaemonOperationProtocolFailure.OUTCOME_UNOBSERVED -> PublicShellExit.IO
            }
        is DaemonOperationFailure.Host ->
            when (cause.reason) {
                ExistingIdeFailure.INVALID_NAME,
                ExistingIdeFailure.INVALID_REQUEST,
                ExistingIdeFailure.REQUEST_TOO_LARGE -> PublicShellExit.INPUT
                ExistingIdeFailure.CONFIGURATION_REJECTED,
                ExistingIdeFailure.HOST_UNAVAILABLE,
                ExistingIdeFailure.APPROVAL_REQUIRED,
                ExistingIdeFailure.APPROVAL_REJECTED -> PublicShellExit.RUNTIME
                ExistingIdeFailure.DESCRIPTOR_REJECTED,
                ExistingIdeFailure.COMPATIBILITY_REJECTED,
                ExistingIdeFailure.RESPONSE_REJECTED,
                ExistingIdeFailure.SCHEMA_UNAVAILABLE,
                ExistingIdeFailure.OPERATION_UNSUPPORTED -> PublicShellExit.PROTOCOL
                // A transport deadline does not prove expiry of the caller's semantic grant.
                ExistingIdeFailure.DEADLINE_EXCEEDED,
                ExistingIdeFailure.TRANSPORT_REJECTED -> PublicShellExit.IO
            }
    }

@Serializable internal data class PublicHostRejection(val failure: HostedEndpointFailure)

internal fun classifyPublicHostRejection(rejection: PublicHostRejection): PublicShellExit =
    when (rejection.failure) {
        HostedEndpointFailure.INVALID_REQUEST,
        HostedEndpointFailure.REQUEST_TOO_LARGE,
        HostedEndpointFailure.REQUEST_INCOMPLETE -> PublicShellExit.INPUT
        HostedEndpointFailure.WRONG_ROOT -> PublicShellExit.WORKSPACE
        HostedEndpointFailure.IO_UNAVAILABLE,
        HostedEndpointFailure.SOCKET_UNAVAILABLE,
        HostedEndpointFailure.DEADLINE_EXCEEDED -> PublicShellExit.IO
        HostedEndpointFailure.RESPONSE_REJECTED -> PublicShellExit.PROTOCOL
        HostedEndpointFailure.RESULT_TOO_LARGE -> PublicShellExit.DELIVERY
        HostedEndpointFailure.ADMISSION_DEADLINE_EXCEEDED,
        HostedEndpointFailure.ADMISSION_CAPACITY_EXCEEDED,
        HostedEndpointFailure.OWNERSHIP_CONFLICT,
        HostedEndpointFailure.DIRECTORY_REJECTED,
        HostedEndpointFailure.PLATFORM_UNAVAILABLE,
        HostedEndpointFailure.UNSAVED_DOCUMENTS,
        HostedEndpointFailure.MODEL_REFRESH_REQUIRED,
        HostedEndpointFailure.APPROVAL_UNAVAILABLE,
        HostedEndpointFailure.APPROVAL_REJECTED -> PublicShellExit.RUNTIME
    }
