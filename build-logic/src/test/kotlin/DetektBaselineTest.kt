import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DetektBaselineTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `native baseline accepts recorded findings and rejects new findings`() {
        temporaryDirectory.resolve("settings.gradle").writeText("rootProject.name = 'detekt-baseline-fixture'\n")
        temporaryDirectory
            .resolve("build.gradle")
            .writeText(
                """
                plugins { id 'dev.detekt' }
                repositories { mavenCentral() }
                detekt {
                    toolVersion = '2.0.0-alpha.6'
                    baseline.set(layout.projectDirectory.file('baseline.xml'))
                }
                """
                    .trimIndent()
            )
        val source = temporaryDirectory.resolve("src/main/kotlin/example/Example.kt")
        source.parent.createDirectories()
        source.writeText("package example\nfun existing(value: Int): Int = value + 42\n")
        val runner = GradleRunner.create().withProjectDir(temporaryDirectory.toFile()).withPluginClasspath()

        val original = runner.withArguments("detekt", "--console=plain").buildAndFail()
        assertTrue(original.output.contains("MagicNumber"), original.output)
        runner.withArguments("detektBaseline", "--console=plain").build()
        runner.withArguments("detekt", "--console=plain").build()

        source.writeText(
            "package example\nfun existing(value: Int): Int = value + 42\nfun added(value: Int): Int = value + 43\n"
        )
        val added = runner.withArguments("detekt", "--console=plain").buildAndFail()
        assertTrue(added.output.contains("MagicNumber"), added.output)
        assertTrue(added.output.contains("Example.kt:3:"), added.output)
    }
}
