package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueAnonymousFormalOracleTest {
    @Test
    fun `explicit anonymous formal uses the body signature instead of the lexical hosts arity`() {
        val host = flowEndpoint("readOffers", 0, 200)
        val owner = anonymousOwner(host)
        val range = flowRange(25, 30)
        val formal =
            ValueFormalParameter.fromCompiler(
                    callable = host,
                    position = index(0),
                    name = name("error"),
                    range = range,
                    executionOwner = owner,
                )
                .flowRefined()
        assertSame(host, formal.callable)
        assertSame(owner, formal.executionOwner)
        assertEquals(ValueFormalOrigin.Declared(range), formal.origin)
        assertEquals(range, formal.range)
        val site = formalSite(host, owner, formal).flowRefined()
        assertEquals(owner.identity, site.identity.execution)
        assertEquals(ValueRole.Formal(ValueFormalPosition.Parameter(formal)), site.role)
    }

    @Test
    fun `implicit formal keeps a real body anchor without a parameter declaration range`() {
        val host = flowEndpoint("readOffers", 0, 200)
        val owner = anonymousOwner(host)
        val formal = ValueFormalParameter.fromCompilerImplicit(owner, index(0), name("it")).flowRefined()
        assertEquals(ValueFormalOrigin.Implicit, formal.origin)
        assertEquals(owner.range, formal.range)
        assertSame(owner, formal.executionOwner)
        assertEquals(owner, formalSite(host, owner, formal).flowRefined().executionOwner)
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.FORMAL_OWNER_MISMATCH),
            formalSite(host, ValueExecutionOwner.declaration(host), formal),
        )
    }

    @Test
    fun `declared formal cannot use a range outside its actual anonymous body`() {
        val host = flowEndpoint("readOffers", 0, 200)
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.PARAMETER_OUTSIDE_CALLABLE),
            ValueFormalParameter.fromCompiler(
                callable = host,
                position = index(0),
                name = name("error"),
                range = flowRange(10, 15),
                executionOwner = anonymousOwner(host),
            ),
        )
    }

    @Test
    fun `anonymous index outside the body signature cannot borrow a lexical parameter`() {
        val host = flowEndpoint(name = "readOffers", start = 0, end = 200, parameters = listOf("String", "String"))
        val owner = anonymousOwner(host)
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.INVALID_PARAMETER),
            ValueFormalParameter.fromCompiler(
                callable = host,
                position = index(1),
                name = name("second"),
                range = flowRange(25, 30),
                executionOwner = owner,
            ),
        )
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.INVALID_PARAMETER),
            ValueFormalParameter.fromCompilerImplicit(owner, index(1), name("it")),
        )
    }

    @Test
    fun `implicit formals reject a named owner a renamed parameter or multiple parameters`() {
        val host = flowEndpoint(name = "readOffers", start = 0, end = 200, parameters = listOf("String"))
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.INVALID_PARAMETER),
            ValueFormalParameter.fromCompilerImplicit(ValueExecutionOwner.declaration(host), index(0), name("it")),
        )
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.INVALID_PARAMETER),
            ValueFormalParameter.fromCompilerImplicit(anonymousOwner(host), index(0), name("item")),
        )
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.INVALID_PARAMETER),
            ValueFormalParameter.fromCompilerImplicit(
                anonymousOwner(host, parameters = listOf("String", "String")),
                index(0),
                name("it"),
            ),
        )
    }

    @Test
    fun `same lexical host and implicit name do not merge distinct anonymous bodies`() {
        val host = flowEndpoint("readOffers", 0, 200)
        val first = ValueFormalParameter.fromCompilerImplicit(anonymousOwner(host), index(0), name("it")).flowRefined()
        val second =
            ValueFormalParameter.fromCompilerImplicit(
                    anonymousOwner(host, start = 110, end = 180),
                    index(0),
                    name("it"),
                )
                .flowRefined()
        assertSame(first.callable, second.callable)
        assertEquals(first.name, second.name)
        assertNotEquals(first.executionOwner, second.executionOwner)
        assertNotEquals(first, second)
    }

    @Test
    fun `foreign anonymous formal cannot become a local formal site`() {
        val host = flowEndpoint("readOffers", 0, 200)
        val foreign = flowEndpoint(name = "readOffers", start = 0, end = 200, generation = 2)
        val formal =
            ValueFormalParameter.fromCompilerImplicit(anonymousOwner(foreign), index(0), name("it")).flowRefined()
        assertEquals(host.valueIdentity, formal.callable.valueIdentity)
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.FORMAL_OWNER_MISMATCH),
            formalSite(host, anonymousOwner(host), formal),
        )
    }

    private fun formalSite(
        host: RelationEndpoint,
        owner: ValueExecutionOwner,
        formal: ValueFormalParameter,
    ) =
        ValueSite.fromCompiler(
            enclosing = host,
            range = formal.range,
            role = ValueRole.Formal(ValueFormalPosition.Parameter(formal)),
            executionOwner = owner,
        )

    private fun anonymousOwner(
        host: RelationEndpoint,
        start: Int = 20,
        end: Int = 100,
        parameters: List<String> = listOf("String"),
    ): ValueExecutionOwner {
        val range = flowRange(start, end)
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(host.file, range),
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = parameters,
                    rawTypeParameterCount = 0,
                )
                .flowRefined() as CanonicalCompilerSignature.Function
        val body = RelationCallableBody.Anonymous.fromCompiler(host.file, range, signature).flowRefined()
        return ValueExecutionOwner.fromCompiler(host, body, host.lease.identity).flowRefined()
    }

    private fun index(value: Int) = ValueArgumentPosition.parse(value).flowRefined()

    private fun name(value: String) = SymbolDiscoveryCandidateName.parse(value).flowRefined()
}
