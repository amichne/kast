package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class RepresentationCollectionInsertionOracleTest {
    @Test
    fun `insertion remains model history and introduces no argument to result compiler transfer`() {
        val origin = ModeledValueProvenanceOracleCase()
        val case = SingleListModelOracleCase(library = origin.model)
        val source = case.argument()
        val arrival = ValueTransfer.fromCompiler(origin.known.site, source, ValueTransferKind.ARGUMENT).flowRefined()
        val before = origin.known.transfer(arrival).flowRefined()
        assertInstanceOf(RepresentationCurrent.Known::class.java, before.branches.single().current)
        val application = case.insert(source)
        val result = before.modeledValue(application).flowRefined()
        val previousHistory = before.branches.single().history
        val history = result.branches.single().history
        assertEquals(previousHistory + application, history)
        assertSame(application, history.last())
        assertEquals(
            previousHistory.filterIsInstance<RepresentationHistory.CompilerTransfer>(),
            history.filterIsInstance<RepresentationHistory.CompilerTransfer>(),
        )
        assertEquals(
            RepresentationCurrent.Unknown(RepresentationUnknownReason.UNMODELED_TRANSFORMATION),
            result.branches.single().current,
        )
        assertSame(application.target, result.site)
    }
}
