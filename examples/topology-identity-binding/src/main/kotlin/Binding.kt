package kast.example.binding

/** Values below are detached. No compiler object or rendered type enters the receipt. */
@JvmInline
value class Epoch private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Epoch {
            require(Regex("[0-9a-f]{64}").matches(raw)) { "invalid semantic epoch" }
            return Epoch(raw)
        }
    }
}

@JvmInline
value class DeclarationId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): DeclarationId {
            require(raw.isNotBlank() && raw.length <= 4096 && '\u0000' !in raw)
            return DeclarationId(raw)
        }
    }
}

/** An existing registry identity, not a new identity computed from the live target. */
data class RegistryEntry(val epoch: Epoch, val identity: DeclarationId)

enum class Difference {
    STALE_EPOCH, DIFFERENT_MODULE, DIFFERENT_ROLE, DIFFERENT_DECLARATION,
    SOURCE_UNAVAILABLE, MULTIPLE_DECLARATIONS, ORIGIN_NOT_ADMITTED,
}

/** Implementations are compiler-effect authorities, not caller-supplied comparison functions. */
sealed interface CompilerComparison {
    data object SameDeclaration : CompilerComparison
    data class Rejected(val difference: Difference) : CompilerComparison
}

/** The implementation may inspect compiler objects only inside the active analysis session. */
fun interface CompilerAuthority<Symbol> {
    fun compare(entry: RegistryEntry, resolved: Symbol): CompilerComparison
}

sealed interface BindingResult {
    class Complete internal constructor(val binding: ProvenBinding) : BindingResult
    data class Rejected(val difference: Difference) : BindingResult
}

/** Proof that this exact registry entry was independently matched in its current epoch. */
class ProvenBinding private constructor(val entry: RegistryEntry) {
    companion object {
        fun <Symbol> bind(
            entry: RegistryEntry,
            currentEpoch: Epoch,
            resolved: Symbol,
            compiler: CompilerAuthority<Symbol>,
        ): BindingResult {
            if (entry.epoch != currentEpoch) return BindingResult.Rejected(Difference.STALE_EPOCH)
            return when (val comparison = compiler.compare(entry, resolved)) {
                CompilerComparison.SameDeclaration -> BindingResult.Complete(ProvenBinding(entry))
                is CompilerComparison.Rejected -> BindingResult.Rejected(comparison.difference)
            }
        }
    }
}
