package io.github.amichne.kast.workspace.intellij.read

import java.nio.file.Path

internal class EpochFixtureRoot private constructor(
    private val path: Path,
) {
    fun contains(candidate: Path): Boolean = candidate.startsWith(path)

    companion object {
        val KAST = EpochFixtureRoot(Path.of("/workspace/kast"))
    }
}

internal sealed interface EpochVfsObservedEvent {
    val paths: List<Path>

    data class Change(val path: Path) : EpochVfsObservedEvent {
        override val paths = listOf(path)
    }

    data class Move(val oldPath: Path, val newPath: Path) : EpochVfsObservedEvent {
        override val paths = listOf(oldPath, newPath)
    }

    data class Rename(val oldPath: Path, val newPath: Path) : EpochVfsObservedEvent {
        override val paths = listOf(oldPath, newPath)
    }
}

internal class EpochVfsMetadataCounter(
    private val root: EpochFixtureRoot,
) {
    var value: Long = 0
        private set

    fun recordEvents(events: Iterable<EpochVfsObservedEvent>) {
        if (events.any { event -> event.paths.any(root::contains) }) value += 1
    }
}

private enum class GradleRootIdentity { EXACT_ROOT, MOVED_ROOT }

private data class EpochFixtureSample(
    val projectCounter: Long,
    val gradleRoot: GradleRootIdentity,
    val lastImportTimestamp: Long,
    val lastSuccessfulImportTimestamp: Long,
    val psiModificationCount: Long,
    val rootFilteredVfsChangeCount: Long,
    val rootModelModificationCount: Long,
    val dumbModeModificationCount: Long,
    val dumbModeState: EpochDumbModeState,
) {
    fun movedSignalsFrom(before: EpochFixtureSample): List<EpochSignalCategory> = buildList {
        if (
            projectCounter != before.projectCounter ||
            gradleRoot != before.gradleRoot ||
            lastImportTimestamp != before.lastImportTimestamp ||
            lastSuccessfulImportTimestamp != before.lastSuccessfulImportTimestamp
        ) add(EpochSignalCategory.PROJECT_MODEL)
        if (psiModificationCount != before.psiModificationCount) add(EpochSignalCategory.PSI)
        if (rootFilteredVfsChangeCount != before.rootFilteredVfsChangeCount) {
            add(EpochSignalCategory.VFS)
        }
        if (rootModelModificationCount != before.rootModelModificationCount) {
            add(EpochSignalCategory.ROOT_MODEL)
        }
        if (
            dumbModeModificationCount != before.dumbModeModificationCount ||
            dumbModeState != before.dumbModeState
        ) add(EpochSignalCategory.DUMB_MODE)
    }

    fun projectModelTransitionsFrom(
        before: EpochFixtureSample,
    ): List<EpochProjectModelTransition> = buildList {
        val rootChanged = gradleRoot != before.gradleRoot
        val importStarted =
            lastImportTimestamp > before.lastImportTimestamp &&
                lastSuccessfulImportTimestamp == before.lastSuccessfulImportTimestamp
        val importCompleted =
            lastImportTimestamp == before.lastImportTimestamp &&
                before.lastSuccessfulImportTimestamp < before.lastImportTimestamp &&
                lastSuccessfulImportTimestamp == lastImportTimestamp
        if (rootChanged) add(EpochProjectModelTransition.GRADLE_ROOT_CHANGED)
        if (importStarted) add(EpochProjectModelTransition.GRADLE_IMPORT_STARTED)
        if (importCompleted) add(EpochProjectModelTransition.GRADLE_IMPORT_COMPLETED)
        if (projectCounter != before.projectCounter && !rootChanged) {
            add(EpochProjectModelTransition.WORKSPACE_MODEL_CHANGED)
        }
    }
}

private data class EpochFixtureCase(
    val before: EpochFixtureSample,
    val after: EpochFixtureSample,
    val dumbModeTimeline: List<EpochDumbModeState>,
    val vfsEventCount: Int = 0,
)

internal fun characterizeEpochCase(caseId: EpochCaseId): EpochCaseDocument {
    val fixture = fixtureCase(caseId)
    val relation = if (fixture.after == fixture.before) {
        EpochSampleRelation.UNCHANGED
    } else {
        EpochSampleRelation.CHANGED
    }
    return EpochCaseDocument(
        caseId = caseId,
        sampleCount = 2,
        movedSignals = fixture.after.movedSignalsFrom(fixture.before),
        projectModelTransitions = fixture.after.projectModelTransitionsFrom(fixture.before),
        dumbModeSamples = listOf(fixture.before.dumbModeState, fixture.after.dumbModeState),
        dumbModeTransitions = fixture.dumbModeTimeline.observedTransitions(),
        vfsEventCount = fixture.vfsEventCount,
        expectedRelation = relation,
        observedRelation = relation,
    )
}

internal fun canonicalEpochSignals(): List<EpochSignalDocument> = listOf(
    EpochSignalDocument(
        EpochSignalCategory.PROJECT_MODEL,
        listOf(
            "WorkspaceModelTopics.CHANGED",
            "ExternalProjectInfo.lastImportTimestamp+lastSuccessfulImportTimestamp",
        ),
        "projectCounter+exactRootImportTimestamps",
        "projectCounterOrExactRootImportTimestampChanged",
    ),
    EpochSignalDocument(
        EpochSignalCategory.PSI,
        listOf("PsiModificationTracker.modificationCount"),
        "modificationCount",
        "modificationCountChanged",
    ),
    EpochSignalDocument(
        EpochSignalCategory.VFS,
        listOf("VirtualFileManager.VFS_CHANGES"),
        "rootFilteredChangeCounter",
        "rootFilteredChangeCounterChanged",
    ),
    EpochSignalDocument(
        EpochSignalCategory.ROOT_MODEL,
        listOf("ProjectRootModificationTracker.modificationCount"),
        "modificationCount",
        "modificationCountChanged",
    ),
    EpochSignalDocument(
        EpochSignalCategory.DUMB_MODE,
        listOf("DumbService.modificationTracker", "DumbService.isDumb"),
        "modificationCount+isDumb",
        "modificationCountOrDumbStateChanged",
    ),
)

internal fun canonicalEpochCases(): List<EpochCaseDocument> = listOf(
    epochCase(EpochCaseId.STABLE, emptyList(), relation = EpochSampleRelation.UNCHANGED),
    epochCase(
        EpochCaseId.WORKSPACE_MODEL_MOVEMENT,
        listOf(EpochSignalCategory.PROJECT_MODEL),
        projectTransitions = listOf(EpochProjectModelTransition.WORKSPACE_MODEL_CHANGED),
    ),
    epochCase(
        EpochCaseId.GRADLE_IMPORT_STARTED,
        listOf(EpochSignalCategory.PROJECT_MODEL),
        projectTransitions = listOf(EpochProjectModelTransition.GRADLE_IMPORT_STARTED),
    ),
    epochCase(
        EpochCaseId.GRADLE_IMPORT_COMPLETED,
        listOf(EpochSignalCategory.PROJECT_MODEL),
        projectTransitions = listOf(EpochProjectModelTransition.GRADLE_IMPORT_COMPLETED),
    ),
    epochCase(
        EpochCaseId.GRADLE_ROOT_MOVEMENT,
        listOf(EpochSignalCategory.PROJECT_MODEL),
        projectTransitions = listOf(EpochProjectModelTransition.GRADLE_ROOT_CHANGED),
    ),
    epochCase(EpochCaseId.PSI_MOVEMENT, listOf(EpochSignalCategory.PSI)),
    epochCase(EpochCaseId.VFS_MOVEMENT, listOf(EpochSignalCategory.VFS)),
    epochCase(EpochCaseId.ROOT_MODEL_MOVEMENT, listOf(EpochSignalCategory.ROOT_MODEL)),
    epochCase(
        EpochCaseId.SMART_DUMB_SMART,
        listOf(EpochSignalCategory.DUMB_MODE),
        dumbTransitions = dumbRoundTrip(),
    ),
    epochCase(
        EpochCaseId.COMBINED_MOVEMENT,
        EpochSignalCategory.entries,
        projectTransitions = listOf(EpochProjectModelTransition.WORKSPACE_MODEL_CHANGED),
        dumbTransitions = dumbRoundTrip(),
    ),
    epochCase(
        EpochCaseId.VFS_EVENT_STORM,
        listOf(EpochSignalCategory.VFS),
        vfsEventCount = 1_000,
    ),
)

private fun fixtureCase(caseId: EpochCaseId): EpochFixtureCase {
    val base = FIXTURE_SAMPLE
    val before = if (caseId == EpochCaseId.GRADLE_IMPORT_COMPLETED) {
        base.copy(lastImportTimestamp = 11, lastSuccessfulImportTimestamp = 10)
    } else {
        base
    }
    val after = when (caseId) {
        EpochCaseId.STABLE -> before
        EpochCaseId.WORKSPACE_MODEL_MOVEMENT -> before.copy(projectCounter = 2)
        EpochCaseId.GRADLE_IMPORT_STARTED -> before.copy(lastImportTimestamp = 11)
        EpochCaseId.GRADLE_IMPORT_COMPLETED -> before.copy(lastSuccessfulImportTimestamp = 11)
        EpochCaseId.GRADLE_ROOT_MOVEMENT -> before.copy(
            projectCounter = 2,
            gradleRoot = GradleRootIdentity.MOVED_ROOT,
        )
        EpochCaseId.PSI_MOVEMENT -> before.copy(psiModificationCount = 2)
        EpochCaseId.VFS_MOVEMENT -> before.copy(rootFilteredVfsChangeCount = 2)
        EpochCaseId.ROOT_MODEL_MOVEMENT -> before.copy(rootModelModificationCount = 2)
        EpochCaseId.SMART_DUMB_SMART -> before.copy(dumbModeModificationCount = 3)
        EpochCaseId.COMBINED_MOVEMENT -> before.copy(
            projectCounter = 2,
            psiModificationCount = 2,
            rootFilteredVfsChangeCount = 2,
            rootModelModificationCount = 2,
            dumbModeModificationCount = 3,
        )
        EpochCaseId.VFS_EVENT_STORM -> before.copy(rootFilteredVfsChangeCount = 1_001)
    }
    val timeline = if (
        caseId == EpochCaseId.SMART_DUMB_SMART || caseId == EpochCaseId.COMBINED_MOVEMENT
    ) {
        listOf(EpochDumbModeState.SMART, EpochDumbModeState.DUMB, EpochDumbModeState.SMART)
    } else {
        listOf(before.dumbModeState, after.dumbModeState)
    }
    return EpochFixtureCase(
        before,
        after,
        timeline,
        if (caseId == EpochCaseId.VFS_EVENT_STORM) 1_000 else 0,
    )
}

private fun epochCase(
    caseId: EpochCaseId,
    movedSignals: List<EpochSignalCategory>,
    projectTransitions: List<EpochProjectModelTransition> = emptyList(),
    relation: EpochSampleRelation = EpochSampleRelation.CHANGED,
    dumbTransitions: List<EpochDumbModeTransition> = emptyList(),
    vfsEventCount: Int = 0,
) = EpochCaseDocument(
    caseId,
    2,
    movedSignals,
    projectTransitions,
    listOf(EpochDumbModeState.SMART, EpochDumbModeState.SMART),
    dumbTransitions,
    vfsEventCount,
    relation,
    relation,
)

private fun List<EpochDumbModeState>.observedTransitions(): List<EpochDumbModeTransition> =
    zipWithNext().mapNotNull { (before, after) ->
        when (before to after) {
            EpochDumbModeState.SMART to EpochDumbModeState.DUMB ->
                EpochDumbModeTransition.SMART_TO_DUMB
            EpochDumbModeState.DUMB to EpochDumbModeState.SMART ->
                EpochDumbModeTransition.DUMB_TO_SMART
            else -> null
        }
    }

private fun dumbRoundTrip() = listOf(
    EpochDumbModeTransition.SMART_TO_DUMB,
    EpochDumbModeTransition.DUMB_TO_SMART,
)

private val FIXTURE_SAMPLE = EpochFixtureSample(
    projectCounter = 1,
    gradleRoot = GradleRootIdentity.EXACT_ROOT,
    lastImportTimestamp = 10,
    lastSuccessfulImportTimestamp = 10,
    psiModificationCount = 1,
    rootFilteredVfsChangeCount = 1,
    rootModelModificationCount = 1,
    dumbModeModificationCount = 1,
    dumbModeState = EpochDumbModeState.SMART,
)
