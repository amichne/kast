package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

class SymbolDiscoveryTest {
    @Test
    fun `native file class and symbol discovery uses matching scope projection and stable order`() {
        SymbolDiscoveryKind.entries.forEach { kind ->
            val fixture = fixture(kind = kind)
            val execution = fixture.execute()

            val complete = (execution as IntellijNativeDiscoveryExecution.Produced)
                .outcome as SymbolDiscoveryOutcome.Complete
            assertEquals(listOf("AItem", "ZItem"), complete.batch.candidates.map { it.name.value })
            assertEquals(fixture.request.scope.lease, complete.batch.lease)
            assertTrue(complete.batch.candidates.all { it.lease == fixture.request.scope.lease })
            assertSame(fixture.scope, fixture.contributor.nameScope)
            assertTrue(fixture.contributor.elementScopes.all { it === fixture.scope })
            assertEquals(listOf("ZItem", "AItem"), fixture.projectedNames)
            assertEquals(
                listOf("ZItem", "AItem", "OutsideItem"),
                fixture.contributor.requestedNames,
            )
            assertTrue(complete.batch.timings.nativeQuery.value > 0L)
            assertTrue(complete.batch.timings.projection.value > 0L)
        }
    }

    @Test
    fun `record byte work and elapsed limits qualify output instead of claiming complete`() {
        val recordOutcome = fixture(resultLimit = 1).execute().outcome()
        assertEquals(listOf(SymbolDiscoveryQualification.RESULT_LIMIT_REACHED), recordOutcome.qualifications())
        assertEquals(1, recordOutcome.batch().candidates.size)

        val byteOutcome = fixture(returnedBytes = 1L).execute().outcome()
        assertEquals(listOf(SymbolDiscoveryQualification.BYTE_LIMIT_REACHED), byteOutcome.qualifications())
        assertTrue(byteOutcome.batch().candidates.isEmpty())

        val workOutcome = fixture(workLimit = 1L).execute().outcome()
        assertEquals(listOf(SymbolDiscoveryQualification.WORK_LIMIT_REACHED), workOutcome.qualifications())
        assertTrue(workOutcome.batch().candidates.isEmpty())

        val timeOutcome = fixture(
            elapsedMillis = 1L,
            clock = StepClock(step = 1_000_000L),
        ).execute().outcome()
        assertEquals(listOf(SymbolDiscoveryQualification.TIME_LIMIT_REACHED), timeOutcome.qualifications())
        assertTrue(timeOutcome.batch().candidates.isEmpty())
    }

    @Test
    fun `same-name collisions remain distinct detached candidates`() {
        val outcome = fixture(collidingNames = true).execute().outcome()

        assertTrue(outcome is SymbolDiscoveryOutcome.Complete)
        assertEquals(
            listOf("CollisionItem", "CollisionItem"),
            outcome.batch().candidates.map { it.name.value },
        )
        assertEquals(
            2,
            outcome.batch().candidates.map { it.location.file.stableValue }.distinct().size,
        )
    }

    @Test
    fun `dumb transitions provider failures and unscoped providers remain explicit`() {
        val initialDumb = fixture(
            environmentState = { IntellijDiscoveryEnvironmentState.DUMB },
        ).execute()
        assertEquals(
            IntellijNativeDiscoveryRejection.DUMB_MODE,
            (initialDumb as IntellijNativeDiscoveryExecution.Rejected).reason,
        )

        var stateChecks = 0
        val transition = fixture(
            environmentState = {
                stateChecks += 1
                if (stateChecks == 1) {
                    IntellijDiscoveryEnvironmentState.READY
                } else {
                    IntellijDiscoveryEnvironmentState.DUMB
                }
            },
        ).execute().outcome()
        assertEquals(
            listOf(SymbolDiscoveryQualification.DUMB_MODE_TRANSITION),
            transition.qualifications(),
        )

        val providerFailure = fixture(providerFails = true).execute().outcome()
        assertEquals(
            listOf(SymbolDiscoveryQualification.PROVIDER_FAILURE),
            providerFailure.qualifications(),
        )

        val unscopedFixture = fixture()
        val unscoped = unscopedFixture.execute(listOf(LegacyContributor())).outcome()
        assertEquals(
            listOf(SymbolDiscoveryQualification.UNSCOPED_PROVIDER),
            unscoped.qualifications(),
        )
    }

    @Test
    fun `platform cancellation is propagated and never converted to complete output`() {
        val fixture = fixture(
            cancellationCheck = { throw ProcessCanceledException() },
        )

        assertThrows<ProcessCanceledException> {
            fixture.execute()
        }
    }

    private fun fixture(
        kind: SymbolDiscoveryKind = SymbolDiscoveryKind.SYMBOL,
        resultLimit: Int = 10,
        returnedBytes: Long = 10_000L,
        workLimit: Long = 100L,
        elapsedMillis: Long = 1_000L,
        environmentState: () -> IntellijDiscoveryEnvironmentState = {
            IntellijDiscoveryEnvironmentState.READY
        },
        cancellationCheck: () -> Unit = {},
        clock: IntellijDiscoveryNanoClock = StepClock(),
        providerFails: Boolean = false,
        collidingNames: Boolean = false,
    ): Fixture {
        val request = request(
            kind = kind,
            resultLimit = resultLimit,
            returnedBytes = returnedBytes,
            workLimit = workLimit,
            elapsedMillis = elapsedMillis,
        )
        val zed = FakeItem("ZItem")
        val noMatch = FakeItem("NoMatch")
        val alpha = FakeItem("AItem")
        val outside = FakeItem("OutsideItem")
        val items = if (collidingNames) {
            listOf(
                FakeItem("CollisionItem", "first"),
                FakeItem("CollisionItem", "second"),
            )
        } else {
            listOf(zed, noMatch, alpha, outside)
        }
        val files = items.associateWith { LightVirtualFile("${it.identity}.kt") }
        val inScopeItems = if (collidingNames) items.toSet() else setOf(zed, noMatch, alpha)
        val inScopeFiles = inScopeItems.mapTo(linkedSetOf(), files::getValue)
        val scope = object : GlobalSearchScope() {
            override fun contains(file: VirtualFile): Boolean = file in inScopeFiles

            override fun isSearchInModuleContent(aModule: Module): Boolean = true

            override fun isSearchInLibraries(): Boolean = false
        }
        val compiledScope = CompiledIntellijSearchScope(
            lease = request.scope.lease,
            scope = request.scope.scope,
            sourceRoots = emptyList(),
            nativeScope = scope,
        )
        val contributor = FakeContributor(
            names = items.map(FakeItem::candidateName),
            items = items.groupBy(FakeItem::candidateName),
            fail = providerFails,
        )
        val projectedNames = mutableListOf<String>()
        val query = IntellijNativeDiscoveryQuery(
            itemFile = { item ->
                files[item as FakeItem]
                    ?.let(IntellijDiscoveryItemFileResult::Found)
                ?: IntellijDiscoveryItemFileResult.Unsupported
            },
            projector = { discoveryRequest, item, file ->
                val fake = item as FakeItem
                projectedNames += fake.candidateName
                SymbolDiscoveryCandidate.fromBoundary(
                    kind = discoveryRequest.kind,
                    rawName = fake.candidateName,
                    lease = discoveryRequest.scope.lease,
                    nativePath = Path.of("/workspace/src/${file.name}"),
                    virtualFileUrl = file.url,
                    rawOffset = if (discoveryRequest.kind == SymbolDiscoveryKind.FILE) {
                        null
                    } else {
                        fake.candidateName.length
                    },
                )
            },
            environmentState = environmentState,
            cancellationCheck = cancellationCheck,
            clock = clock,
        )
        return Fixture(
            request = request,
            scope = scope,
            compiledScope = compiledScope,
            contributor = contributor,
            projectedNames = projectedNames,
            query = query,
        )
    }

    private fun request(
        kind: SymbolDiscoveryKind,
        resultLimit: Int,
        returnedBytes: Long,
        workLimit: Long,
        elapsedMillis: Long,
    ): SymbolDiscoveryRequest {
        val workspaceRoot =
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val lease = SemanticReadLease(
            workspaceRoot = workspaceRoot,
            generation = EvidenceGeneration.parse(19L).refined(),
        )
        return SymbolDiscoveryRequest(
            scope = SymbolSearchScopeRequest(
                lease = lease,
                scope = SymbolSearchScope.Workspace(
                    sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
                    libraries = SymbolLibraryPolicy.EXCLUDE,
                ),
            ),
            kind = kind,
            pattern = io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
                .parse("Item")
                .refined(),
            budget = SymbolDiscoveryBudget(
                resources = ResourceBudget(
                    resultLimit = ResultLimit.parse(resultLimit).refined(),
                    workUnitLimit = WorkUnitLimit.parse(workLimit).refined(),
                    elapsedTimeLimit = ElapsedTimeLimitMillis.parse(elapsedMillis).refined(),
                ),
                returnedBytes = SymbolDiscoveryByteLimit.parse(returnedBytes).refined(),
            ),
        )
    }

    private data class Fixture(
        val request: SymbolDiscoveryRequest,
        val scope: GlobalSearchScope,
        val compiledScope: CompiledIntellijSearchScope,
        val contributor: FakeContributor,
        val projectedNames: List<String>,
        val query: IntellijNativeDiscoveryQuery,
    ) {
        fun execute(
            contributors: List<ChooseByNameContributor> = listOf(contributor),
        ): IntellijNativeDiscoveryExecution =
            query.discover(compiledScope, request, contributors)
    }

    private class FakeContributor(
        private val names: List<String>,
        private val items: Map<String, List<FakeItem>>,
        private val fail: Boolean,
    ) : ChooseByNameContributorEx {
        var nameScope: GlobalSearchScope? = null
        val elementScopes = mutableListOf<GlobalSearchScope>()
        val requestedNames = mutableListOf<String>()

        override fun processNames(
            processor: Processor<in String>,
            scope: GlobalSearchScope,
            filter: IdFilter?,
        ) {
            if (fail) {
                error("provider failed")
            }
            nameScope = scope
            names.forEach { name ->
                if (!processor.process(name)) {
                    return
                }
            }
        }

        override fun processElementsWithName(
            name: String,
            processor: Processor<in NavigationItem>,
            parameters: FindSymbolParameters,
        ) {
            requestedNames += name
            elementScopes += parameters.searchScope
            items[name].orEmpty().forEach { item ->
                if (!processor.process(item)) return
            }
        }
    }

    private class LegacyContributor : ChooseByNameContributor {
        override fun getNames(
            project: com.intellij.openapi.project.Project?,
            includeNonProjectItems: Boolean,
        ): Array<String> = emptyArray()

        override fun getItemsByName(
            name: String,
            pattern: String,
            project: com.intellij.openapi.project.Project?,
            includeNonProjectItems: Boolean,
        ): Array<NavigationItem> = emptyArray()
    }

    private data class FakeItem(
        val candidateName: String,
        val identity: String = candidateName,
    ) : NavigationItem {
        override fun getName(): String = candidateName

        override fun getPresentation(): ItemPresentation? = null
    }

    private class StepClock(
        private val step: Long = 100L,
    ) : IntellijDiscoveryNanoClock {
        private var current = 0L

        override fun now(): Long = current.also { current += step }
    }

    private fun IntellijNativeDiscoveryExecution.outcome(): SymbolDiscoveryOutcome =
        (this as IntellijNativeDiscoveryExecution.Produced).outcome

    private fun SymbolDiscoveryOutcome.batch() = when (this) {
        is SymbolDiscoveryOutcome.Complete -> batch
        is SymbolDiscoveryOutcome.Qualified -> batch
    }

    private fun SymbolDiscoveryOutcome.qualifications(): List<SymbolDiscoveryQualification> =
        when (this) {
            is SymbolDiscoveryOutcome.Complete -> emptyList()
            is SymbolDiscoveryOutcome.Qualified -> qualifications.values.toList()
        }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
