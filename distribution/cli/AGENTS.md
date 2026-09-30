# Native management CLI

This module owns the public `kast` executable. Keep its command graph limited to status, version, supported harness registration and plugin installation, upgrade, and uninstall. The operational JVM CLI and transport launchers are private managed payloads.

Keep receipt facts separate from passive runtime observations. Status must not start Java, prepare workspaces, or contact release servers. Admit release manifests and external registration documents before effects; preserve foreign files and registrations. Use the existing installer and service shutdown boundary for replacement and removal.

`kast plugin codex` verifies the complete bundled marketplace and installed MCP launcher before asking Codex to install `kast@kast`. Codex owns plugin configuration, cache, and removal. Preserve foreign marketplace and plugin identities; do not add a second Kast ownership receipt or edit Codex files directly.

The managed root contains one ordinary `installation` directory. Its stable `bin`, `share`, `config`, and `state` paths survive replacement. Locks and the management receipt remain in the managed root. Do not introduce version directories, selection links, or native legacy-layout fallback; the installer owns admitted migration and transaction recovery.

The connection owner may migrate an exactly recorded legacy MCP command only during a proven `PAYLOAD_COMMITTED` legacy replacement transaction. Preserve the canonical replacement receipt, old launcher digest, filesystem identities, selector target, and observed Codex command proof. Restore the complete host configuration and management receipt if the connection transaction fails; the installer seals replacement recovery.

Run `./gradlew :distribution:cli:test :distribution:cli:nativeCompile` for changes here, then the affected release, architecture, JSON, and knowledge guards.
