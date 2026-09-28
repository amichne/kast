# Native management CLI

This module owns the public `kast` executable. Keep its command graph limited to status, version, supported harness registration, upgrade, and uninstall. The operational JVM CLI and transport launchers are private managed payloads.

Keep receipt facts separate from passive runtime observations. Status must not start Java, prepare workspaces, or contact release servers. Admit release manifests and external registration documents before effects; preserve foreign files and registrations. Use the existing installer and service shutdown boundary for replacement and removal.

Run `./gradlew :distribution:cli:test :distribution:cli:nativeCompile` for changes here, then the affected release, architecture, JSON, and knowledge guards.
