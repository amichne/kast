package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.ProviderFailureCode
import io.github.amichne.kast.appserver.ide.*

internal fun ExistingIdeFailure.providerFailure(): ProviderFailureCode =
    when (this) {
        ExistingIdeFailure.CONFIGURATION_REJECTED -> ProviderFailureCode.IDE_CONFIGURATION_REJECTED
        ExistingIdeFailure.INVALID_NAME -> ProviderFailureCode.IDE_INVALID_NAME
        ExistingIdeFailure.INVALID_REQUEST -> ProviderFailureCode.IDE_INVALID_REQUEST
        ExistingIdeFailure.HOST_UNAVAILABLE -> ProviderFailureCode.IDE_HOST_UNAVAILABLE
        ExistingIdeFailure.DESCRIPTOR_REJECTED -> ProviderFailureCode.IDE_DESCRIPTOR_REJECTED
        ExistingIdeFailure.RESPONSE_REJECTED -> ProviderFailureCode.IDE_RESPONSE_REJECTED
        ExistingIdeFailure.REQUEST_TOO_LARGE -> ProviderFailureCode.IDE_REQUEST_TOO_LARGE
        ExistingIdeFailure.DEADLINE_EXCEEDED -> ProviderFailureCode.IDE_DEADLINE_EXCEEDED
        ExistingIdeFailure.TRANSPORT_REJECTED -> ProviderFailureCode.IDE_TRANSPORT_REJECTED
        ExistingIdeFailure.SCHEMA_UNAVAILABLE -> ProviderFailureCode.IDE_SCHEMA_UNAVAILABLE
        ExistingIdeFailure.OPERATION_UNSUPPORTED -> ProviderFailureCode.IDE_OPERATION_UNSUPPORTED
        ExistingIdeFailure.APPROVAL_REQUIRED -> ProviderFailureCode.IDE_APPROVAL_REQUIRED
        ExistingIdeFailure.APPROVAL_REJECTED -> ProviderFailureCode.IDE_APPROVAL_REJECTED
    }

internal fun CanonicalRootFailure.providerFailure(): ProviderFailureCode =
    when (this) {
        CanonicalRootFailure.START_UNAVAILABLE -> ProviderFailureCode.WORKSPACE_START_UNAVAILABLE
        CanonicalRootFailure.START_NOT_DIRECTORY -> ProviderFailureCode.WORKSPACE_START_NOT_DIRECTORY
        CanonicalRootFailure.ROOT_MARKER_NOT_FOUND -> ProviderFailureCode.WORKSPACE_ROOT_MARKER_NOT_FOUND
        CanonicalRootFailure.INVALID_ROOT_MARKER -> ProviderFailureCode.WORKSPACE_INVALID_ROOT_MARKER
    }
