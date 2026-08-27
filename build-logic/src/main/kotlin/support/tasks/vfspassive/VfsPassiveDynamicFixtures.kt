package support.tasks.vfspassive

import java.security.MessageDigest

internal fun fixedDynamicEvidence() = REQUIRED_DYNAMIC_TESTS.map { expected ->
    DynamicTestClassDocument(
        expected.authority,
        expected.className,
        expected.testCount,
        0,
        "0".repeat(64),
        DynamicProofOutcome.COMPLETE,
    )
}

internal fun zeroProhibitedEffects() = ProhibitedEffectCountsDocument(
    authority = DynamicProofAuthority.DYNAMIC_INSTRUMENTATION,
    refresh = 0,
    gradleImport = 0,
    repositoryWalk = 0,
    sourceHash = 0,
    blockingRead = 0,
    listenerSemanticWork = 0,
    edtSemanticWork = 0,
    kastProcessStart = 0,
    total = 0,
)

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

internal val REQUIRED_DYNAMIC_TESTS = listOf(
    ExpectedDynamicTest(DynamicProofAuthority.SINGLE_FLIGHT, "io.github.amichne.kast.runtime.ide.read.SingleFlightNegativeTest", 5),
    ExpectedDynamicTest(DynamicProofAuthority.SINGLE_FLIGHT, "io.github.amichne.kast.runtime.ide.read.SingleFlightTest", 5),
    ExpectedDynamicTest(DynamicProofAuthority.CANCELLABLE_READ, "io.github.amichne.kast.runtime.ide.read.CancellableReadNegativeTest", 8),
    ExpectedDynamicTest(DynamicProofAuthority.CANCELLABLE_READ, "io.github.amichne.kast.runtime.ide.read.CancellableReadTest", 3),
    ExpectedDynamicTest(DynamicProofAuthority.EPOCH_REVALIDATION, "io.github.amichne.kast.runtime.ide.read.EpochRevalidationNegativeTest", 6),
    ExpectedDynamicTest(DynamicProofAuthority.EPOCH_REVALIDATION, "io.github.amichne.kast.runtime.ide.read.EpochRevalidationTest", 3),
    ExpectedDynamicTest(DynamicProofAuthority.VFS_EVENT_STORM, "io.github.amichne.kast.workspace.intellij.read.EpochSignalCharacterizationTest", 4),
    ExpectedDynamicTest(DynamicProofAuthority.VFS_EVENT_STORM, "io.github.amichne.kast.workspace.intellij.read.IdeProjectReadEpochTest", 7),
)
