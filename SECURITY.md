# Security reporting

Report suspected vulnerabilities through
[GitHub private vulnerability reporting](https://github.com/amichne/kast/security/advisories/new).
If private reporting is unavailable, ask the repository owner for a private
channel without including exploit details in a public issue.

Include the Kast version and source revision, macOS architecture, IDEA and JBR
versions, affected boundary, a minimal reproduction, and the expected versus
observed behavior. Remove credentials and proprietary source from attachments.

Security fixes target the latest supported release. Before 1.0, older 0.x
versions may require an upgrade. The 1.x contract covers macOS on Apple silicon,
Kotlin Gradle repositories, and the documented IntelliJ platform line. The Codex
broker is a read-only preview and has a separate upstream protocol boundary.

The release gate admits exact archive digests before publication. GitHub build
provenance binds uploaded assets to the publishing workflow and source revision.
The receipt reports executable acceptance; neither checksum nor provenance alone
proves semantic correctness.
