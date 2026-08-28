package io.github.amichne.kast.ide.endpoint

import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorV2
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointLocation
import io.github.amichne.kast.runtime.ide.host.HostedIdeRuntime

/**
 * Exact-root complete-runtime endpoint state that owns no filesystem or socket effect yet.
 */
class PreparedIdeEndpoint internal constructor(
    val canonicalRoot: IdeEndpointCanonicalRoot,
    internal val runtime: HostedIdeRuntime,
    val descriptor: IdeEndpointDescriptorV2,
    val location: IdeEndpointLocation,
    internal val compatibilityPolicy: IdeHostCompatibilityPolicy,
)
