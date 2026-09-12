package io.github.amichne.kast.appserver

// Filesystem effects are shared with the installed sidecar through distribution:managed.
internal typealias BrokerEndpointAliasReceipt =
    io.github.amichne.kast.distribution.managed.endpoint.InstalledEndpointAliasReceipt

internal typealias BrokerEndpointAliases = io.github.amichne.kast.distribution.managed.endpoint.InstalledEndpointAliases

internal typealias BrokerUpstreamDirectoryReceipt =
    io.github.amichne.kast.distribution.managed.endpoint.InstalledUpstreamDirectoryReceipt

internal typealias BrokerUpstreamDirectories =
    io.github.amichne.kast.distribution.managed.endpoint.InstalledUpstreamDirectories
