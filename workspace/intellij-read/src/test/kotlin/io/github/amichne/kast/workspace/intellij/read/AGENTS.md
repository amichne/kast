# Existing Project admission tests

This package proves the KVP-014 boundary without opening, importing, refreshing, or waiting for an
IntelliJ project.

- Use a dynamic `Project` proxy only as the opaque live handle retained by admission.
- Drive platform observations through `ExistingProjectObservationPort`; every rejection must prove
  the exact observed prefix and that later stages were not called.
- Keep the supported host tuple and canonical report bytes in the shared typed fixture.
- The positive test must prove that the admitted value exposes no public `Project` member.
- Compare the generated report as exact bytes. Do not add a second JSON model or parser here.

Run:

```shell
./gradlew :workspace:intellij-read:test --tests '*ExistingProjectAdmissionNegativeTest'
./gradlew :workspace:intellij-read:test --tests '*ExistingProjectAdmissionTest'
```
