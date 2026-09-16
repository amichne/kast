package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.SourceReferenceFailure
import io.github.amichne.kast.source.contract.SourceSelectorTokenFailure

internal fun CanonicalSelectorDecodingFailure.sourceFailure(): SourceReferenceFailure =
    when (this) {
        CanonicalSelectorDecodingFailure.REVALIDATION_WRONG_KIND -> SourceReferenceFailure.REVALIDATION_WRONG_KIND
        CanonicalSelectorDecodingFailure.REVALIDATION_UNRETAINED -> SourceReferenceFailure.REVALIDATION_UNRETAINED
        CanonicalSelectorDecodingFailure.REVALIDATION_EXPIRED -> SourceReferenceFailure.REVALIDATION_EXPIRED
        CanonicalSelectorDecodingFailure.REVALIDATION_CAPACITY -> SourceReferenceFailure.REVALIDATION_CAPACITY
        CanonicalSelectorDecodingFailure.REVALIDATION_WORK_LIMIT_REACHED ->
            SourceReferenceFailure.REVALIDATION_WORK_LIMIT_REACHED
        CanonicalSelectorDecodingFailure.REVALIDATION_TIME_LIMIT_REACHED ->
            SourceReferenceFailure.REVALIDATION_TIME_LIMIT_REACHED
        CanonicalSelectorDecodingFailure.REVALIDATION_RETIRED -> SourceReferenceFailure.REVALIDATION_RETIRED
        CanonicalSelectorDecodingFailure.REVALIDATION_CAPTURE_UNAVAILABLE ->
            SourceReferenceFailure.REVALIDATION_CAPTURE_UNAVAILABLE
        CanonicalSelectorDecodingFailure.REVALIDATION_WORKSPACE_MISMATCH ->
            SourceReferenceFailure.REVALIDATION_WORKSPACE_MISMATCH
        CanonicalSelectorDecodingFailure.REVALIDATION_OWNER_MISMATCH ->
            SourceReferenceFailure.REVALIDATION_OWNER_MISMATCH
        CanonicalSelectorDecodingFailure.REVALIDATION_WORKSPACE_NOT_READY ->
            SourceReferenceFailure.REVALIDATION_WORKSPACE_NOT_READY
        CanonicalSelectorDecodingFailure.REVALIDATION_BASIS_MOVED -> SourceReferenceFailure.REVALIDATION_BASIS_MOVED
        CanonicalSelectorDecodingFailure.REVALIDATION_CONTENT_CHANGED ->
            SourceReferenceFailure.REVALIDATION_CONTENT_CHANGED
        CanonicalSelectorDecodingFailure.REVALIDATION_CONTENT_UNCOMMITTED ->
            SourceReferenceFailure.REVALIDATION_CONTENT_UNCOMMITTED
        CanonicalSelectorDecodingFailure.REVALIDATION_SCOPE_REJECTED ->
            SourceReferenceFailure.REVALIDATION_SCOPE_REJECTED
        CanonicalSelectorDecodingFailure.REVALIDATION_DECLARATION_MISSING ->
            SourceReferenceFailure.REVALIDATION_DECLARATION_MISSING
        CanonicalSelectorDecodingFailure.REVALIDATION_UNSUPPORTED_DECLARATION ->
            SourceReferenceFailure.REVALIDATION_UNSUPPORTED_DECLARATION
        CanonicalSelectorDecodingFailure.REVALIDATION_AMBIGUOUS -> SourceReferenceFailure.REVALIDATION_AMBIGUOUS
        CanonicalSelectorDecodingFailure.REVALIDATION_COMPILER_IDENTITY_CHANGED ->
            SourceReferenceFailure.REVALIDATION_COMPILER_IDENTITY_CHANGED
        CanonicalSelectorDecodingFailure.REVALIDATION_COMPILER_UNAVAILABLE ->
            SourceReferenceFailure.REVALIDATION_COMPILER_UNAVAILABLE

        CanonicalSelectorDecodingFailure.INVALID_TOKEN_STRUCTURE -> SourceReferenceFailure.MALFORMED
        CanonicalSelectorDecodingFailure.INVALID_PAYLOAD_ENCODING -> SourceReferenceFailure.INVALID_PAYLOAD_ENCODING
        CanonicalSelectorDecodingFailure.PAYLOAD_DIGEST_MISMATCH -> SourceReferenceFailure.PAYLOAD_DIGEST_MISMATCH
        CanonicalSelectorDecodingFailure.MALFORMED_DOCUMENT -> SourceReferenceFailure.MALFORMED
        CanonicalSelectorDecodingFailure.INVALID_DOCUMENT -> SourceReferenceFailure.INVALID_DOCUMENT
        CanonicalSelectorDecodingFailure.INCOMPATIBLE_WORKSPACE -> SourceReferenceFailure.FOREIGN_WORKSPACE
        CanonicalSelectorDecodingFailure.INCOMPATIBLE_AUTHORITY -> SourceReferenceFailure.INCOMPATIBLE_AUTHORITY
        CanonicalSelectorDecodingFailure.UNAVAILABLE -> SourceReferenceFailure.UNAVAILABLE
        CanonicalSelectorDecodingFailure.STALE_AUTHORITY -> SourceReferenceFailure.STALE_AUTHORITY
        CanonicalSelectorDecodingFailure.UNSUPPORTED_REFERENCE_VERSION -> SourceReferenceFailure.UNSUPPORTED_VERSION
        CanonicalSelectorDecodingFailure.LIVE_AUTHORITY_REQUIRED -> SourceReferenceFailure.LIVE_AUTHORITY_REQUIRED
    }

internal fun SourceSelectorTokenFailure.sourceFailure(): SourceReferenceFailure =
    when (this) {
        SourceSelectorTokenFailure.TOKEN_TOO_LONG -> SourceReferenceFailure.TOKEN_TOO_LONG
        SourceSelectorTokenFailure.INVALID_TOKEN_STRUCTURE -> SourceReferenceFailure.MALFORMED
        SourceSelectorTokenFailure.INVALID_PAYLOAD_ENCODING -> SourceReferenceFailure.INVALID_PAYLOAD_ENCODING
        SourceSelectorTokenFailure.PAYLOAD_DIGEST_MISMATCH -> SourceReferenceFailure.PAYLOAD_DIGEST_MISMATCH
        SourceSelectorTokenFailure.MALFORMED_PAYLOAD -> SourceReferenceFailure.MALFORMED
        SourceSelectorTokenFailure.SNAPSHOT_REJECTED -> SourceReferenceFailure.SNAPSHOT_REJECTED
        SourceSelectorTokenFailure.SELECTOR_REJECTED -> SourceReferenceFailure.SELECTOR_REJECTED
        SourceSelectorTokenFailure.SELECTOR_TOO_DEEP -> SourceReferenceFailure.SELECTOR_TOO_DEEP
    }
