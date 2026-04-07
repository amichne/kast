# Kotlin Antipatterns and Code Smells

## Table of Contents

- [Type Safety Violations](#type-safety-violations)
- [Concurrency Issues](#concurrency-issues)
- [API Design Flaws](#api-design-flaws)
- [Performance Antipatterns](#performance-antipatterns)
- [Architecture Smells](#architecture-smells)
- [Enterprise-Specific Issues](#enterprise-specific-issues)
- [Testing Antipatterns](#testing-antipatterns)
- [Documentation Gaps](#documentation-gaps)

## Type Safety Violations

### Unsafe Casts and Null Handling
- **Using `!!` operator**: Almost always a code smell. Indicates missing null-safety guarantees.
- **Unchecked casts (`as`)**: Use `as?` with proper handling or refactor to eliminate the cast.
- **Platform types leaking**: JVM interop should be wrapped with explicit nullability annotations.

### Any/Star Projections
- **Excessive `Any` usage**: Loss of type information, especially in library APIs.
- **Star projections in public APIs**: `List<*>` forces consumers into unsafe territory.
- **Generic type erasure workarounds**: Reified generics or witness patterns preferred over runtime checks.

## Concurrency Issues

### Mutable Shared State
- **var in concurrent contexts**: Use immutable data structures or explicit synchronization.
- **Unprotected collections**: MutableList/Map accessed from multiple threads without locks.
- **Missing @Volatile**: Shared flags without proper memory barriers.

### Coroutine Misuse
- **GlobalScope usage**: Almost never appropriate; ties lifecycle to application lifetime.
- **Blocking in suspend functions**: Use withContext(Dispatchers.IO) for blocking operations.
- **Missing cancellation handling**: Suspend functions should respect Job cancellation.
- **Structured concurrency violations**: Launching coroutines without proper scope management.

## API Design Flaws

### Overly Permissive Types
- **Exposing mutable collections**: Return List, not MutableList. Use immutable interfaces.
- **var properties in data classes**: Breaks immutability guarantees and thread safety.
- **Open classes by default**: Seal or finalize unless extension is intentional.

### Leaky Abstractions
- **Implementation details in interfaces**: Keep internal concerns internal.
- **Concrete types in API boundaries**: Depend on abstractions (interfaces), not implementations.
- **Exception types leaking internals**: Wrap third-party exceptions in domain exceptions.

### Poor Error Handling
- **Throwing checked exceptions from Kotlin**: Use Result, Either, or sealed class hierarchies.
- **Generic exceptions**: Use specific exception types or typed error representations.
- **Missing error context**: Exceptions should carry enough information for debugging.

## Performance Antipatterns

### Allocation Hotspots
- **Excessive object creation in loops**: Cache or use object pools for hot paths.
- **String concatenation in loops**: Use StringBuilder or joinToString.
- **Defensive copying overuse**: Profile before optimizing; immutability often eliminates need.

### Collection Operations
- **Multiple passes over collections**: Chain operations to single pass where possible.
- **Sequences misuse**: Use for large collections or when intermediate results not needed.
- **Missing inline for HOFs**: Performance-critical higher-order functions should be inline.

### Reflection Overuse
- **Runtime reflection for type info**: Use compile-time mechanisms (sealed classes, enums).
- **Annotation processing at runtime**: Move to compile-time with KSP/kapt.

## Architecture Smells

### Tight Coupling
- **Singleton abuse**: Makes testing hard, hides dependencies.
- **Static state**: Breaks testability and concurrent usage.
- **Constructor over-injection**: >5 dependencies suggests SRP violation.

### Missing Abstractions
- **Concrete class dependencies**: Depend on interfaces for flexibility and testing.
- **No separation of concerns**: Business logic mixed with infrastructure.
- **God objects**: Classes with too many responsibilities.

### Inappropriate Inheritance
- **Inheritance for code reuse**: Prefer composition.
- **Deep inheritance hierarchies**: >3 levels is usually a smell.
- **Non-sealed hierarchies**: Use sealed classes for closed type hierarchies.

## Enterprise-Specific Issues

### Missing Observability Hooks
- **No metrics/tracing integration points**: APIs should expose hooks for instrumentation.
- **Opaque execution**: No way to observe internal state or behavior.
- **Missing context propagation**: Correlation IDs, trace contexts should flow through.

### Backwards Compatibility Breaks
- **Removing public APIs**: Use @Deprecated with migration path first.
- **Changing sealed class hierarchies**: Adding cases breaks exhaustive when.
- **Modifying data class properties**: Changes equality/hashCode semantics.

### Configuration Rigidity
- **Hardcoded values**: Configuration should be externalized and overridable.
- **No feature flags**: Large systems need runtime control.
- **Missing version negotiation**: APIs should handle multiple versions gracefully.

## Testing Antipatterns

### Untestable Design
- **Final classes with no interfaces**: Cannot mock.
- **Hidden dependencies**: Using ServiceLoader or global state.
- **Time dependencies**: Use Clock abstraction, not Instant.now().

### Poor Test Coverage
- **Only happy path tests**: Error cases and edge cases are critical.
- **No property-based testing**: Complex logic benefits from generative testing.
- **Missing integration tests**: Unit tests don't catch composition issues.

## Documentation Gaps

### Missing KDoc
- **Public APIs without documentation**: All public members need KDoc.
- **No usage examples**: Complex APIs need code samples.
- **Missing @since/@deprecated**: Version info aids migration.

### Undocumented Invariants
- **Thread-safety assumptions**: Document synchronization requirements.
- **Preconditions/postconditions**: Use contracts or document explicitly.
- **Performance characteristics**: Big-O complexity for collections/algorithms.
