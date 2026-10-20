package io.github.amichne.kast.relation.intellij

/** K2 source facts precede nullable alias admission. These captures apply no value model or NonNull predicate. */
class NativePacketNullableAliasProbeTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-nullable-alias-probe-jdk"

    fun testOriginalRequireNotNullReceiverAndLetCallbackKeepExactCompilerAliases() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        NativePacketNullableAliasSource(project, getOrCreateProjectBaseDir(), fixture).capture()
    }

    fun testOriginalElvisErrorAndBindThrowKeepTheirActualSourceBranches() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        NativePacketNullableBranchSource(project, getOrCreateProjectBaseDir(), fixture).capture()
    }
}
