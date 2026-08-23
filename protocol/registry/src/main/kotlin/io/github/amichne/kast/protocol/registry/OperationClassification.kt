package io.github.amichne.kast.protocol.registry

/**
 * The closed execution lane whose authority and cost remain visible before admission.
 */
enum class OperationLane {
    METADATA,
    INDEX_LOOKUP,
    SCOPED_SEMANTIC_READ,
    BOUNDED_RELATION_READ,
    REGISTERED_LONG_WORK,
    DERIVED_WRITE,
    SOURCE_WRITE,
}

/**
 * The strongest effect authority an operation may require from a later runtime binding.
 */
enum class OperationEffect {
    NONE,
    INTELLIJ_READ,
    INTELLIJ_READ_AND_PERSISTENCE_WRITE,
    INTELLIJ_WRITE,
    FILESYSTEM_WRITE,
    PERSISTENCE_WRITE,
    WORKSPACE_MODEL_WRITE,
    PROCESS_CONTROL,
}

/**
 * The broad resource-cost class used to route and admit an operation.
 */
enum class OperationCost {
    HOST_NEUTRAL,
    BOUNDED_READ,
    PHYSICAL_EFFECT,
    RUNTIME_ORCHESTRATION,
}

/**
 * The narrowest domain scope against which an operation is evaluated.
 */
enum class OperationScope {
    RUNTIME,
    WORKSPACE,
    PROJECT,
    FILE,
    SYMBOL,
}

/**
 * Whether a bounded operation must be complete or may return explicitly qualified evidence.
 */
enum class CompletenessPolicy {
    COMPLETE_REQUIRED,
    QUALIFIED_ALLOWED,
}
