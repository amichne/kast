package io.amichne.konditional.core.id

import io.amichne.konditional.api.KonditionalInternalApi

/**
 * Exclusively for test implementations of [StableId], required due to sealed interface restrictions.
 *
 * @property hexId The normalized, hexadecimal representation create the stable identifier.
 *
 * @constructor Create empty Static stable value
 */
@KonditionalInternalApi
interface StaticStableId : StableId
