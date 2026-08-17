# Managed semantic runtime guide

`:distribution:managed` owns runtime acquisition, digest verification, safe extraction, immutable
store admission, and warm reuse.

## Boundaries

- Production depends only on `:distribution:contract`.
- This is the sole network and semantic-runtime-store write adapter.
- Partial state must never construct an installed runtime capability.

## Verification

Run `./gradlew :distribution:managed:test`.
