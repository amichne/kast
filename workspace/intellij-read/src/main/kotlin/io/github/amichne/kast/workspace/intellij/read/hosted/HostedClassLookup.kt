package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.intellij.read.DetachedIdeWorkspaceModel
import java.util.Collections

@JvmInline
value class HostedClassName private constructor(val value: String) {
    companion object {
        internal fun parse(raw: String): Refinement<HostedClassName, HostedQueryFailure> =
            if (
                raw.isNotEmpty() &&
                    raw.toByteArray(Charsets.UTF_8).size <= 512 &&
                    (raw.first().isLetter() || raw.first() == '_') &&
                    raw.all { it.isLetterOrDigit() || it == '_' }
            )
                Refinement.Refined(HostedClassName(raw))
            else Refinement.Rejected(HostedQueryFailure.INVALID_SELECTION)
    }
}

/** Exact short-name lookup within supported cached source folders of one explicit project. */
class HostedClassLookup private constructor(val root: CanonicalWorkspaceRoot, val name: HostedClassName) {
    companion object {
        fun parse(root: CanonicalWorkspaceRoot, name: String): Refinement<HostedClassLookup, HostedQueryFailure> =
            when (val admitted = HostedClassName.parse(name)) {
                is Refinement.Refined -> Refinement.Refined(HostedClassLookup(root, admitted.value))
                is Refinement.Rejected -> admitted
            }
    }
}

internal const val HOSTED_MAX_INDEX_CANDIDATES = 32

internal enum class HostedIndexCollection {
    CONTINUE,
    STOP,
}

/** Complete bounded index candidates or one terminal overflow; never silently truncated. */
internal class HostedIndexCandidates<Value>(private val limits: ReadLimits = ReadLimits.Default) {
    private sealed interface State<out Value> {
        data class Collecting<Value>(val values: MutableList<Value>) : State<Value>

        data object Overflow : State<Nothing>
    }

    private var state: State<Value> = State.Collecting(mutableListOf())

    fun accept(value: Value): HostedIndexCollection =
        when (val current = state) {
            State.Overflow -> HostedIndexCollection.STOP
            is State.Collecting ->
                if (current.values.size == limits[ReadLimitParameter.HOST_CLASS_CANDIDATES].value) {
                    state = State.Overflow
                    HostedIndexCollection.STOP
                } else {
                    current.values.add(value)
                    HostedIndexCollection.CONTINUE
                }
        }

    fun finish(): Refinement<List<Value>, HostedQueryFailure> =
        when (val current = state) {
            State.Overflow -> Refinement.Rejected(HostedQueryFailure.RESULT_LIMIT_EXCEEDED)
            is State.Collecting -> Refinement.Refined(Collections.unmodifiableList(ArrayList(current.values)))
        }
}

class HostedIndexedClasses
internal constructor(val lookup: HostedClassLookup, declarations: List<HostedCompilerDeclaration>) {
    val declarations: List<HostedCompilerDeclaration> = Collections.unmodifiableList(ArrayList(declarations))
}

class HostedIndexPublication
internal constructor(
    val endpoint: HostedQueryEndpoint,
    val epoch: ProjectReadEpoch<*>,
    val model: DetachedIdeWorkspaceModel,
    val classes: HostedIndexedClasses,
)

sealed interface HostedIndexResult {
    data class Published(val publication: HostedIndexPublication) : HostedIndexResult

    data class Rejected(
        val failure: HostedQueryFailure,
        val stage: HostedQueryStage = HostedQueryStage.REQUEST_ADMISSION,
    ) : HostedIndexResult
}
