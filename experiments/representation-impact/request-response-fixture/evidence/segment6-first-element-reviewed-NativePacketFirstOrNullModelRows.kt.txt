package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueElementIndex
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite

/** Independent expected rows await the shared reviewed-application API. They do not assert a compiler transfer. */
internal object NativePacketFirstOrNullModelRows {
    enum class Row {
        ORIGINAL_ANY_ELEMENT,
        ORIGINAL_ELEMENT_ZERO,
        ORIGINAL_ELEMENT_ONE,
        SOURCE_SAME_NAME,
    }

    enum class Condition {
        FIRST_ELEMENT_EXISTS,
        FORBIDDEN_UNSELECTED_ELEMENT,
        FORBIDDEN_SOURCE_CALLABLE,
    }

    fun inputPath(row: Row): ValueAccessPath =
        ValueAccessPath.fromSegments(
                listOf(
                    when (row) {
                        Row.ORIGINAL_ANY_ELEMENT -> ValuePathSegment.AnyElement
                        Row.ORIGINAL_ELEMENT_ZERO,
                        Row.SOURCE_SAME_NAME -> ValuePathSegment.Element(ValueElementIndex.parse(0).nativeRefined())
                        Row.ORIGINAL_ELEMENT_ONE -> ValuePathSegment.Element(ValueElementIndex.parse(1).nativeRefined())
                    }
                )
            )
            .nativeRefined()

    fun condition(row: Row): Condition =
        when (row) {
            Row.ORIGINAL_ANY_ELEMENT,
            Row.ORIGINAL_ELEMENT_ZERO -> Condition.FIRST_ELEMENT_EXISTS
            Row.ORIGINAL_ELEMENT_ONE -> Condition.FORBIDDEN_UNSELECTED_ELEMENT
            Row.SOURCE_SAME_NAME -> Condition.FORBIDDEN_SOURCE_CALLABLE
        }

    sealed interface Result {
        data class Selected(val site: ValueSite) : Result

        data object NoApplication : Result
    }

    fun requiredResult(row: Row, source: ValueSite): Result =
        when (row) {
            Row.ORIGINAL_ANY_ELEMENT,
            Row.ORIGINAL_ELEMENT_ZERO -> Result.Selected(selectedResult(source))
            Row.ORIGINAL_ELEMENT_ONE,
            Row.SOURCE_SAME_NAME -> Result.NoApplication
        }

    /** List<String> has no source field suffix. The selected result keeps the empty suffix and existing predicates. */
    private fun selectedResult(source: ValueSite): ValueSite {
        val receiver = source.role as ValueRole.Receiver
        return ValueSite.fromCompiler(
                enclosing = source.enclosing,
                range = receiver.call.range,
                role = ValueRole.ExpressionResult,
                executionOwner = source.executionOwner,
                valuePath = ValueAccessPath.Whole,
                callStack = source.callStack,
                predicates = source.predicates,
            )
            .nativeRefined()
    }
}
