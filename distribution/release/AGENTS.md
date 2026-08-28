# Hosted release ownership

This directory owns KVP-035's deterministic control-plus-plugin release assembly and validation.

- Publish exactly the matched control archive and standalone IDE plugin plus checksum sidecars.
- Reject semantic-runtime assets or manifest entries, platform payload in the plugin, version
  mismatch, unsafe archive paths, and combined payloads above 80 MiB.
- Emit the canonical KVP-035 release report under `build/reports/ide-hosted`; receipt issuance
  remains owned by the delivery proof boundary.
- The atomic proof consumes canonical legal and negative reports; neither is a manually writable
  completion state.

Run `./gradlew verifyIdeHostedReleaseNegative assembleIdeHostedRelease verifyIdeHostedRelease`.
