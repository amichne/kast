package io.github.amichne.kast.evidence.sqlite.historical.cli

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.topology.contract.historical.GitObjectId
import io.github.amichne.kast.topology.contract.historical.HistoricalContext
import io.github.amichne.kast.topology.contract.historical.HistoricalCoverage
import io.github.amichne.kast.topology.contract.historical.HistoricalCoverageStatus
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclaration
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclarationId
import io.github.amichne.kast.topology.contract.historical.HistoricalDependency
import io.github.amichne.kast.topology.contract.historical.HistoricalDigest
import io.github.amichne.kast.topology.contract.historical.HistoricalFactFamily
import io.github.amichne.kast.topology.contract.historical.HistoricalFile
import io.github.amichne.kast.topology.contract.historical.HistoricalFileProvenance
import io.github.amichne.kast.topology.contract.historical.HistoricalHeader
import io.github.amichne.kast.topology.contract.historical.HistoricalLimitation
import io.github.amichne.kast.topology.contract.historical.HistoricalModule
import io.github.amichne.kast.topology.contract.historical.HistoricalModuleId
import io.github.amichne.kast.topology.contract.historical.HistoricalName
import io.github.amichne.kast.topology.contract.historical.HistoricalOccurrence
import io.github.amichne.kast.topology.contract.historical.HistoricalOccurrenceOwner
import io.github.amichne.kast.topology.contract.historical.HistoricalProfile
import io.github.amichne.kast.topology.contract.historical.HistoricalReferenceContext
import io.github.amichne.kast.topology.contract.historical.HistoricalReferenceKind
import io.github.amichne.kast.topology.contract.historical.HistoricalRepositoryId
import io.github.amichne.kast.topology.contract.historical.HistoricalRevision
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshot
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInput
import io.github.amichne.kast.topology.contract.historical.HistoricalSourceSet
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import org.junit.jupiter.api.assertInstanceOf

/** Explicit synthetic producer evidence. These tables establish no native compiler claim. */
internal object HistoricalUsageOracle {
    val sources =
        mapOf(
            "contracts/Api.kt" to
                "package sample\n\nclass Api\nfun api(value: Int): Int = value\n" +
                    "fun api(value: String): String = value\n",
            "feature/Main.kt" to "package feature\nimport sample.Api\nfun use(): Int = sample.api(1) + sample.api(2)\n",
            "feature/Test.kt" to "package feature\nfun testUse(): Int = sample.api(3)\n",
            "isolated/Api.kt" to "package sample\n\nclass Api\n",
        )
    val classApi = id("sample.Api", "contracts/Api.kt", 16, 25)
    val integerApi = id("sample.api(Int)", "contracts/Api.kt", 26, 58)
    val stringApi = id("sample.api(String)", "contracts/Api.kt", 59, 97)
    val isolatedApi = id("sample.Api", "isolated/Api.kt", 16, 25)
    private val mainOwner = id("feature.use()", "feature/Main.kt", 34, 80)
    private val testOwner = id("feature.testUse()", "feature/Test.kt", 16, 50)

    val expectedRows =
        listOf(
            "feature.main|contracts.main|feature/Main.kt|30|33|sample.Api|contracts/Api.kt|16|25|IMPORT|file",
            "feature.main|contracts.main|feature/Main.kt|58|61|sample.api(Int)|" +
                "contracts/Api.kt|26|58|REFERENCE|declaration",
            "feature.main|contracts.main|feature/Main.kt|74|77|sample.api(Int)|" +
                "contracts/Api.kt|26|58|REFERENCE|declaration",
            "feature.test|contracts.main|feature/Test.kt|44|47|sample.api(Int)|" +
                "contracts/Api.kt|26|58|REFERENCE|declaration",
        )

    fun snapshot(profile: HistoricalProfile = HistoricalProfile.REFERENCES_V1): HistoricalSnapshot {
        val modules =
            listOf("contracts.main", "feature.main", "feature.test", "isolated.main", "empty.main", "rootless").map {
                raw ->
                HistoricalModule(
                    module(raw),
                    name(raw.substringBefore('.')),
                    name(raw),
                    if (raw == "rootless") HistoricalSourceSet.Rootless
                    else HistoricalSourceSet.Named(name(raw.substringAfter('.'))),
                )
            }
        val files =
            listOf(
                    "contracts/Api.kt" to "contracts.main",
                    "feature/Main.kt" to "feature.main",
                    "feature/Test.kt" to "feature.test",
                    "isolated/Api.kt" to "isolated.main",
                )
                .map { (path, owner) ->
                    HistoricalFile(
                        path(path),
                        WorkspaceSourceContentHash.parse("a".repeat(64)).value(),
                        module(owner),
                        HistoricalFileProvenance.Tracked(GitObjectId.parse("b".repeat(40)).value()),
                    )
                }
        return HistoricalSnapshotInput(
                header(profile),
                modules,
                listOf(HistoricalDependency(module("feature.main"), module("contracts.main"))),
                files,
                declarations(),
                if (profile == HistoricalProfile.REFERENCES_V1) occurrences() else emptyList(),
                coverage(modules, profile),
            )
            .admit()
            .value()
    }

    fun request(
        snapshot: HistoricalSnapshot,
        size: Int = 1,
        cursor: UsageCursorDocument? = null,
        targets: List<HistoricalDeclarationId> = listOf(classApi, integerApi),
        sources: List<String> = listOf("feature.main", "feature.test"),
    ) =
        UsageRequestDocument(
            sources,
            targets.map { HistoricalReferenceDocument(snapshot.identity.value, it.document()) },
            size,
            cursor,
        )

    private fun declarations() =
        listOf(
            HistoricalDeclaration(classApi, name("Api"), name("sample.Api"), CompilerSymbolKind.CLASSLIKE),
            HistoricalDeclaration(integerApi, name("api"), name("sample.api(Int):Int"), CompilerSymbolKind.FUNCTION),
            HistoricalDeclaration(
                stringApi,
                name("api"),
                name("sample.api(String):String"),
                CompilerSymbolKind.FUNCTION,
            ),
            HistoricalDeclaration(isolatedApi, name("Api"), name("sample.Api"), CompilerSymbolKind.CLASSLIKE),
            HistoricalDeclaration(mainOwner, name("use"), name("feature.use():Int"), CompilerSymbolKind.FUNCTION),
            HistoricalDeclaration(
                testOwner,
                name("testUse"),
                name("feature.testUse():Int"),
                CompilerSymbolKind.FUNCTION,
            ),
        )

    private fun occurrences() =
        listOf(
            HistoricalOccurrence(
                path("feature/Main.kt"),
                range(30, 33),
                classApi,
                HistoricalOccurrenceOwner.FileScoped(HistoricalReferenceContext.IMPORT),
                HistoricalReferenceKind.IMPORT,
                HistoricalReferenceContext.IMPORT,
            ),
            HistoricalOccurrence(
                path("feature/Main.kt"),
                range(58, 61),
                integerApi,
                HistoricalOccurrenceOwner.Declaration(mainOwner),
                HistoricalReferenceKind.REFERENCE,
                HistoricalReferenceContext.CODE,
            ),
            HistoricalOccurrence(
                path("feature/Main.kt"),
                range(74, 77),
                integerApi,
                HistoricalOccurrenceOwner.Declaration(mainOwner),
                HistoricalReferenceKind.REFERENCE,
                HistoricalReferenceContext.CODE,
            ),
            HistoricalOccurrence(
                path("feature/Test.kt"),
                range(44, 47),
                integerApi,
                HistoricalOccurrenceOwner.Declaration(testOwner),
                HistoricalReferenceKind.REFERENCE,
                HistoricalReferenceContext.CODE,
            ),
        )

    private fun coverage(modules: List<HistoricalModule>, profile: HistoricalProfile) = modules.flatMap { module ->
        HistoricalFactFamily.entries.map { family ->
            HistoricalCoverage(
                module.id,
                family,
                when {
                    family == HistoricalFactFamily.REFERENCES && profile == HistoricalProfile.STRUCTURE_V1 ->
                        HistoricalCoverageStatus.NotCollected
                    family == HistoricalFactFamily.REFERENCES && module.id.value == "feature.test" ->
                        HistoricalCoverageStatus.Qualified(HistoricalLimitation.UNRESOLVED_TARGET)
                    family == HistoricalFactFamily.REFERENCES && module.id.value == "rootless" ->
                        HistoricalCoverageStatus.OutsideProfile(HistoricalLimitation.ROOTLESS)
                    else -> HistoricalCoverageStatus.Complete
                },
            )
        }
    }

    private fun header(profile: HistoricalProfile) =
        HistoricalHeader(
            HistoricalRevision(
                HistoricalRepositoryId.parse("fixture:offline-usage").value(),
                GitObjectId.parse("1".repeat(40)).value(),
                GitObjectId.parse("2".repeat(40)).value(),
            ),
            HistoricalContext(digest("3"), digest("4"), digest("5")),
            profile,
            name("explicit-oracle-1"),
        )

    private fun id(compiler: String, file: String, start: Int, end: Int) =
        HistoricalDeclarationId(
            CompilerSymbolIdentity.parse(compiler).value(),
            path(file),
            range(start, end),
        )

    private fun module(raw: String) = HistoricalModuleId.parse(raw).value()

    private fun name(raw: String) = HistoricalName.parse(raw).value()

    private fun path(raw: String) = WorkspaceSourcePath.parse(raw).value()

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()

    private fun digest(raw: String) = HistoricalDigest.parse(raw.repeat(64)).value()

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
