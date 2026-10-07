package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable

/** Original child failures retain the legacy leaves and can never contain a completion policy verdict. */
@Serializable sealed interface QueryOriginalFailureDocument : QueryRunRejection
