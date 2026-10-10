package support.tasks.nativefixtures

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeFixtureLaunchPolicyTest {
    @Test
    fun `pinned host selects official module access flags without unrelated JVM options`() {
        val encoded = Json.encodeToString(NativeFixtureProductInfo("262.9437.185", listOf(
            NativeFixtureLaunch(NativeFixtureOperatingSystem.MACOS, listOf(
                "-Xmx2g", "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED",
            )),
            NativeFixtureLaunch(NativeFixtureOperatingSystem.LINUX, listOf("--add-opens=other/module=ALL-UNNAMED")),
        )))

        val result = NativeFixtureLaunchPolicy.select(encoded, "262.9437.185", "Mac OS X") as NativeFixtureLaunchDecision.Ready

        assertEquals(listOf(
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
            "--enable-native-access=ALL-UNNAMED",
        ), result.moduleAccess.arguments)
    }

    @Test
    fun `mismatched distribution rejects before launch`() {
        assertRejected(valid(), "262.1", "Linux", NativeFixtureLaunchFailure.BUILD_MISMATCH)
    }

    @Test
    fun `unknown host cannot inherit Linux permissions`() {
        assertRejected(valid(), "262.9437.185", "FreeBSD", NativeFixtureLaunchFailure.UNSUPPORTED_HOST_OS)
    }

    @Test
    fun `absent host launch rejects`() {
        assertRejected(valid(), "262.9437.185", "Windows 11", NativeFixtureLaunchFailure.HOST_LAUNCH_UNAVAILABLE)
    }

    @Test
    fun `unknown metadata platform rejects`() {
        assertRejected(valid().replace("Linux", "FreeBSD"), "262.9437.185", "Linux", NativeFixtureLaunchFailure.INVALID_PRODUCT_INFO)
    }

    @Test
    fun `missing arguments rejects rather than silently opening nothing`() {
        val encoded = Json.encodeToString(IncompatibleProductInfo(
            "262.9437.185", listOf(LaunchWithoutArguments(NativeFixtureOperatingSystem.LINUX)),
        ))
        assertRejected(encoded,
            "262.9437.185", "Linux", NativeFixtureLaunchFailure.INVALID_PRODUCT_INFO)
    }

    @Test
    fun `wrong argument type rejects rather than filtering malformed entries`() {
        val encoded = Json.encodeToString(IncompatibleProductInfo(
            "262.9437.185", listOf(LaunchWithBooleanArguments(NativeFixtureOperatingSystem.LINUX, listOf(true))),
        ))
        assertRejected(encoded,
            "262.9437.185", "Linux", NativeFixtureLaunchFailure.INVALID_PRODUCT_INFO)
    }

    private fun valid(): String = Json.encodeToString(NativeFixtureProductInfo(
        "262.9437.185", listOf(NativeFixtureLaunch(NativeFixtureOperatingSystem.LINUX, emptyList())),
    ))

    private fun assertRejected(encoded: String, build: String, os: String, failure: NativeFixtureLaunchFailure) {
        assertEquals(NativeFixtureLaunchDecision.Rejected(failure), NativeFixtureLaunchPolicy.select(encoded, build, os))
    }
}

// Intentionally incompatible DTOs prove boundary rejection without handwritten JSON.
@Serializable
private data class IncompatibleProductInfo<Launch>(val buildNumber: String, val launch: List<Launch>)

@Serializable
private data class LaunchWithoutArguments(val os: NativeFixtureOperatingSystem)

@Serializable
private data class LaunchWithBooleanArguments(
    val os: NativeFixtureOperatingSystem,
    val additionalJvmArguments: List<Boolean>,
)
