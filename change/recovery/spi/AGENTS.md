# Change recovery SPI guide

`:change:recovery:spi` owns the narrow durable recovery-preparation port. It performs no physical
effect and exchanges only detached recovery contract values and closed outcomes.

## Invariants

- The port accepts only exact recovery material from a successful revalidation.
- A prepared result means the physical adapter completed durability before returning.
- No path, handle, callback, source-write capability, or arbitrary exception crosses the port.

## Verification

Run `./gradlew :change:recovery:spi:test`.
