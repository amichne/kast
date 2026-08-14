# Change verification SPI module guide

`:change:verify:spi` owns the detached KIP-035 command, current-generation observation, finite
verification failures, compiler-backed verification port, and narrow terminal journal port. It
owns no IntelliJ object, workspace publication effect, journal implementation, recovery artifact,
or public transport.

## Invariants

- Verification commands retain the exact plan and a strictly newer published generation.
- Current compiler context is observed separately from the plan's G0 compiler context.
- Only a successful executor result may issue matched declaration identity and satisfied-obligation
  proof; expected failures are closed typed data.
- Terminal completion consumes that exact observation capability directly and projects its full
  publication and typed observed identity into the durable receipt. Enumerable obligations are not
  a completion input.
- Ports expose no Boolean, nullable, string, or arbitrary-exception expected-failure protocol.

## Verification

Run `./gradlew :change:verify:spi:test --tests '*AddDeclaration*Verif*'`.
