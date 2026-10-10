package support.tasks.nativefixtures

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/** The product-info boundary reads only the fixed fields needed to launch an indexed fixture. */
@Serializable
internal data class NativeFixtureProductInfo(
    val buildNumber: String,
    val launch: List<NativeFixtureLaunch>,
)

@Serializable
internal data class NativeFixtureLaunch(
    val os: NativeFixtureOperatingSystem,
    val additionalJvmArguments: List<String>,
)

@Serializable
internal enum class NativeFixtureOperatingSystem {
    @SerialName("macOS") MACOS,
    @SerialName("Windows") WINDOWS,
    @SerialName("Linux") LINUX,
}

internal enum class NativeFixtureLaunchFailure {
    INVALID_PRODUCT_INFO,
    BUILD_MISMATCH,
    UNSUPPORTED_HOST_OS,
    HOST_LAUNCH_UNAVAILABLE,
}

internal sealed interface NativeFixtureLaunchDecision {
    data class Ready(val moduleAccess: NativeFixtureModuleAccess) : NativeFixtureLaunchDecision

    data class Rejected(val failure: NativeFixtureLaunchFailure) : NativeFixtureLaunchDecision
}

internal class NativeFixtureModuleAccess internal constructor(val arguments: List<String>)

internal object NativeFixtureLaunchPolicy {
    // Product metadata has other fixed fields owned by JetBrains; this projection never interprets them.
    private val json = Json { ignoreUnknownKeys = true }

    fun select(encoded: String, pinnedBuild: String, osName: String): NativeFixtureLaunchDecision {
        val info =
            try {
                json.decodeFromString<NativeFixtureProductInfo>(encoded)
            } catch (_: SerializationException) {
                return NativeFixtureLaunchDecision.Rejected(NativeFixtureLaunchFailure.INVALID_PRODUCT_INFO)
            }
        if (info.buildNumber != pinnedBuild) {
            return NativeFixtureLaunchDecision.Rejected(NativeFixtureLaunchFailure.BUILD_MISMATCH)
        }
        val platform =
            when {
                osName.startsWith("Mac") -> NativeFixtureOperatingSystem.MACOS
                osName.startsWith("Windows") -> NativeFixtureOperatingSystem.WINDOWS
                osName == "Linux" -> NativeFixtureOperatingSystem.LINUX
                else -> return NativeFixtureLaunchDecision.Rejected(NativeFixtureLaunchFailure.UNSUPPORTED_HOST_OS)
            }
        val launches = info.launch.filter { it.os == platform }
        if (launches.isEmpty()) {
            return NativeFixtureLaunchDecision.Rejected(NativeFixtureLaunchFailure.HOST_LAUNCH_UNAVAILABLE)
        }
        val arguments =
            launches.flatMap { it.additionalJvmArguments }
                .filter { it.startsWith("--add-opens=") || it.startsWith("--add-exports=") }
                .distinct() + "--enable-native-access=ALL-UNNAMED"
        return NativeFixtureLaunchDecision.Ready(NativeFixtureModuleAccess(arguments))
    }
}

abstract class NativeFixtureJvmArguments : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val productInfo: RegularFileProperty

    @get:Input abstract val pinnedBuild: Property<String>

    @get:Input abstract val osName: Property<String>

    override fun asArguments(): Iterable<String> =
        when (
            val decision = NativeFixtureLaunchPolicy.select(
                productInfo.get().asFile.readText(), pinnedBuild.get(), osName.get(),
            )
        ) {
            is NativeFixtureLaunchDecision.Ready -> decision.moduleAccess.arguments
            is NativeFixtureLaunchDecision.Rejected ->
                throw GradleException("Native fixture launch rejected: ${decision.failure}")
        }
}
