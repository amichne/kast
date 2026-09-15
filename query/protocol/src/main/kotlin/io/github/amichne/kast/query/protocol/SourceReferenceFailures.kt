package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.SourceReferenceFailure
import io.github.amichne.kast.source.contract.SourceSelectorTokenFailure

internal fun CanonicalSelectorDecodingFailure.sourceFailure(): SourceReferenceFailure =
    when (this) {
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
