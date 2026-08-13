# Change planning SPI module guide

`:change:plan:spi` owns the narrow add-declaration planning and detached evidence-source ports.
It owns no planner implementation, host object, persistence, approval state, or mutation authority.

## Dependency boundary

- Production depends only on `:change:contract` and `:workspace:contract`.
- Do not import IntelliJ, filesystem, JDBC, transport, legacy `analysis-api`, backend, adapter,
  service-locator, apply, recovery, or verification implementations.
- Ports exchange only detached contract values and finite outcomes.

## Verification ladder

1. Run `./gradlew :change:plan:spi:test`.
2. Run `./gradlew :change:plan:intellij:test`.
3. Run `./gradlew verifyKastArchitecture --configuration-cache`.
