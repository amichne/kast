# Endpoint lifecycle tests

The publication selectors are the executable KVP-024 product proof; the retirement selectors own
KVP-025 lifecycle and cleanup behavior.

- The negative selector names every canonical rejection and verifies occupied artifacts remain
  untouched and duplicate publication occurs before a second bind.
- The positive selector uses the real JDK Unix-domain socket and atomic descriptor publisher,
  connects physically, reads canonical bytes, and re-admits descriptor v2.
- Retirement proves exact-once cleanup, pending-accept shutdown, explicit service disposal,
  identity-mismatch preservation, and disposal racing publication.
- Model-capture admission proves that exact clean-import ownership incompleteness remains deferred
  without allowing a terminal failure to hide in a mixed failure set.
- Shared endpoint fixtures may be package-internal only when both receipt-bound task selectors use
  the same physical JDK publication boundary.

Run the two canonical selectors for the active endpoint task, followed by `:ide-plugin:check`.
