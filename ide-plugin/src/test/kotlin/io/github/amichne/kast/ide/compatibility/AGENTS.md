# IDE host compatibility tests

This directory proves the KVP-012 compatibility tuple and generated-report boundary.

- Positive tests must admit only the declared hosted tuple and preserve all six identities plus the
  exact four-operation capability set.
- Negative tests must name the exact closed failure for malformed identities and documents,
  unsupported or unknown capabilities, duplicate/missing/reordered sets, and tuple mismatches.
- Read the generated report supplied by the Gradle test input rather than reproducing its bytes.
- Prove that live hosted admission has no raw-document or arbitrary-candidate input and derives its
  retained candidate-policy relation only from the generated KVP-012 authority.

Run `./gradlew :ide-plugin:generateIdeHostCompatibilityReport :ide-plugin:test --tests
'*IdeHostCompatibilityTest' --tests '*IdeHostCompatibilityNegativeTest'`.
