package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits

/** Bounded vocabulary at the native effect boundary. No names, paths, references, or PSI. */
enum class IntellijReadCounter {
    IMPORTED_PROJECTS,
    IDEA_MODULES,
    SELECTED_GRADLE_MODULES,
    FOREIGN_GRADLE_MODULES,
    SOURCE_ROOTS,
    NAMES_VISITED,
    NAMES_MATCHED,
    LEXICAL_CANDIDATES_RETAINED,
    LEXICAL_CANDIDATES_REPLACED,
    LEXICAL_CANDIDATES_DROPPED,
    CANDIDATES_COLLECTED,
    SCOPE_FILTERED,
    CANDIDATES_PROJECTED,
    COMPILER_REFINEMENTS,
    COMPILER_REFINEMENTS_REJECTED,
    COMPILER_NATIVE_CALLABLE_IDENTITIES,
    COMPILER_ENUM_ENTRY_MEMBER_IDENTITIES,
    COMPILER_CALLABLE_IDENTITIES_UNAVAILABLE,
    RELATION_CANDIDATES,
    RELATION_FACTS,
    RELATION_ITEMS_OMITTED,
    REVALIDATION_FILES_HASHED,
    REVALIDATION_BYTES_HASHED,
    REVALIDATION_CAPTURE_REJECTED,
    REVALIDATION_LOCATORS_RETAINED,
    REVALIDATION_LOCATORS_REJECTED,
    REVALIDATION_LOOKUPS,
    REVALIDATION_LOOKUPS_REJECTED,
    REFERENCE_HANDLES_ISSUED,
    REFERENCE_HANDLES_RESTORED,
    REFERENCE_HANDLES_REJECTED,
    REFERENCE_INLINE_CAPACITY,
    REFERENCE_INLINE_COLLISION,
}

enum class IntellijReadContributor {
    NONE,
    EXACT_INDEX,
    SCOPED_DECLARATIONS,
    KOTLIN_CLASS,
    KOTLIN_CLASS_SYMBOL,
    KOTLIN_FUNCTION_SYMBOL,
    KOTLIN_PROPERTY_SYMBOL,
    KOTLIN_TYPE_ALIAS,
    OTHER,
}

enum class IntellijReadTermination {
    COMPLETE,
    NAME_CAP,
    CANDIDATE_CAP,
    WORK_LIMIT,
    TIME_LIMIT,
    RESULT_LIMIT,
    BYTE_LIMIT,
    RESPONSE_BYTE_LIMIT,
    UNSCOPED_PROVIDER,
    PROVIDER_FAILURE,
    UNSUPPORTED_ITEM,
    EXACT_REFINEMENT_UNAVAILABLE,
    INDEXING,
    DISPOSED,
    MODULE_ADMISSION_LIMIT,
    SOURCE_ROOT_ADMISSION_LIMIT,
    K2_UNRESOLVED_SYMBOL,
    K2_CALLABLE_CONTAINER_UNSUPPORTED,
    K2_ENUM_OWNER_UNAVAILABLE,
    K2_ENUM_INITIALIZER_MISMATCH,
    K2_ENUM_IDENTITY_UNAVAILABLE,
    K2_UNNAMED_CALLABLE,
    K2_SYMBOL_WITHOUT_PSI,
    K2_NON_KOTLIN_PSI,
    CALLEE_OUTSIDE_NATIVE_SCOPE,
    TARGET_OUTSIDE_PACKAGE,
    NON_KOTLIN_REFERENCE,
    LIBRARY_POLICY_EXCLUSION,
    RELATION_CALL_OWNER_UNSUPPORTED,
    RELATION_UNRESOLVED_TARGET,
    RELATION_UNSUPPORTED_ITEM,
    RELATION_PROVIDER_INCOMPLETE,
    RELATION_PROVIDER_STALLED,
}

enum class IntellijReadStage {
    HOSTED,
    MODEL_CAPTURE,
    DISCOVERY,
    EXACT_REFINEMENT,
    RELATION,
    SOURCE,
    TRANSPORT,
}

enum class IntellijReadUnexpectedKind {
    RUNTIME,
    LINKAGE,
}

data class IntellijReadUnexpectedFailure
private constructor(
    val stage: IntellijReadStage,
    val kind: IntellijReadUnexpectedKind,
    val exceptionType: String,
    val adapterFrames: List<String>,
) {
    companion object {
        fun capture(
            stage: IntellijReadStage,
            failure: Throwable,
            limits: ReadLimits = ReadLimits.Default,
        ): IntellijReadUnexpectedFailure =
            IntellijReadUnexpectedFailure(
                stage,
                if (failure is LinkageError) IntellijReadUnexpectedKind.LINKAGE else IntellijReadUnexpectedKind.RUNTIME,
                failure.javaClass.name.take(limits[ReadLimitParameter.DIAGNOSTIC_TEXT_CHARACTERS].value),
                failure.stackTrace
                    .asSequence()
                    .filter { it.className.startsWith("io.github.amichne.kast.") }
                    .take(limits[ReadLimitParameter.DIAGNOSTIC_FRAMES].value)
                    .map {
                        "${it.className}.${it.methodName}:${it.lineNumber}"
                            .take(limits[ReadLimitParameter.DIAGNOSTIC_TEXT_CHARACTERS].value)
                    }
                    .toList(),
            )
    }
}

/** Request-local diagnostic capability; owners choose whether to observe. */
interface IntellijReadObservation {
    fun unexpected(failure: IntellijReadUnexpectedFailure) {}

    fun count(
        counter: IntellijReadCounter,
        contributor: IntellijReadContributor = IntellijReadContributor.NONE,
        amount: Int = 1,
    )

    fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor = IntellijReadContributor.NONE)

    data object None : IntellijReadObservation {
        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) = Unit

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
    }
}
