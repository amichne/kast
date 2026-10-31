package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class BuildSelectionTest {
    private val identity = InstalledBuildIdentity("v1.2.3", "1.2.3", "a".repeat(40), "b".repeat(40))

    @Test
    fun `fresh default is stable and a pin never implicitly moves`() {
        val request = (admitBuildRequest(null, null, null) as SelectionAdmission.Admitted).value
        assertEquals(
            SelectionAdmission.Admitted(BuildRequest.Stable),
            resolveRecordedPolicy(request, UpdatePolicy.TrackStable),
        )
        assertEquals(
            SelectionAdmission.Admitted(BuildRequest.Main),
            resolveRecordedPolicy(request, UpdatePolicy.TrackMain),
        )
        assertEquals(
            SelectionAdmission.Rejected(BuildSelectionFailure.PINNED_BUILD),
            resolveRecordedPolicy(request, UpdatePolicy.PinnedBuild(identity)),
        )
        assertEquals(
            SelectionAdmission.Admitted(BuildRequest.Stable),
            resolveRecordedPolicy(BuildRequest.Stable, UpdatePolicy.PinnedBuild(identity)),
        )
    }

    @Test
    fun `selector parsing rejects conflicts and noncanonical revisions`() {
        assertEquals(
            SelectionAdmission.Rejected(BuildSelectionFailure.SELECTORS_CONFLICT),
            admitBuildRequest("main", "1.2.3", null),
        )
        for (raw in listOf("abc", "A".repeat(40), "a".repeat(13), "../revision")) assertEquals(
            SelectionAdmission.Rejected(BuildSelectionFailure.SELECTOR_INVALID),
            admitBuildRequest(null, null, raw),
        )
        for (raw in listOf("v1.2.3", "1.2", "01.2.3", "1.2.3;touch /tmp/no")) assertEquals(
            SelectionAdmission.Rejected(BuildSelectionFailure.SELECTOR_INVALID),
            admitBuildRequest(null, raw, null),
        )
        assertInstanceOf(SelectionAdmission.Admitted::class.java, admitBuildRequest(null, "1.2.3", null))
    }

    @Test
    fun `one captured catalog preserves main and original build identities`() {
        val first = entry("abcdef123456" + "a".repeat(28))
        val second =
            entry("b".repeat(40))
                .copy(platformTargets = listOf("other-target"), version = "0.0.2", releaseTag = "developer-v0.0.2")
        val catalog =
            (AdmittedBuildCatalog.decode(managementJson.encodeToString(MainBuildCatalog(1, listOf(first, second))))
                    as SelectionAdmission.Admitted)
                .value
        val request = (admitBuildRequest(null, null, "abcdef123456") as SelectionAdmission.Admitted).value
        assertEquals(SelectionAdmission.Admitted(first), catalog.select(request, "macos-aarch64"))
        assertEquals("c".repeat(40), first.buildRevision)
        assertEquals(
            SelectionAdmission.Rejected(BuildSelectionFailure.PLATFORM_UNAVAILABLE),
            catalog.select(BuildRequest.Main, "other-target"),
        )
        assertEquals(
            SelectionAdmission.Rejected(BuildSelectionFailure.BINARY_UNAVAILABLE),
            catalog.select(
                (admitBuildRequest(null, null, "d".repeat(40)) as SelectionAdmission.Admitted).value,
                "macos-aarch64",
            ),
        )
    }

    @Test
    fun `ambiguous displayed prefixes never pick the first match`() {
        val builds = listOf(entry("abcdef123456" + "a".repeat(28)), entry("abcdef123456" + "b".repeat(28)))
        val catalog =
            (AdmittedBuildCatalog.decode(managementJson.encodeToString(MainBuildCatalog(1, builds)))
                    as SelectionAdmission.Admitted)
                .value
        val request = (admitBuildRequest(null, null, "abcdef123456") as SelectionAdmission.Admitted).value
        assertEquals(
            SelectionAdmission.Rejected(BuildSelectionFailure.AMBIGUOUS_COMMIT),
            catalog.select(request, "macos-aarch64"),
        )
    }

    private fun entry(main: String) =
        MainBuildEntry(
            main,
            "c".repeat(40),
            "d".repeat(40),
            "developer-v0.0.1",
            "0.0.1",
            listOf("macos-aarch64"),
            BuildRecordReference("candidate.json", "e".repeat(64)),
            BuildRecordReference("promotion.json", "f".repeat(64)),
        )
}
