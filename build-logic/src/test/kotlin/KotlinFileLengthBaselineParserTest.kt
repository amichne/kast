import conventions.KotlinFileLengthBaseline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class KotlinFileLengthBaselineParserTest {
    @Test
    fun `only normalized relative Kotlin source paths are admitted`() {
        listOf(
                "/Source.kt",
                "../Source.kt",
                "a/../Source.kt",
                "./Source.kt",
                "a//Source.kt",
                "a\\Source.kt",
                "C:/Source.kt",
                "Source.java",
            )
            .forEach { path ->
                assertEquals(
                    KotlinFileLengthBaseline.Admission.Rejected(KotlinFileLengthBaseline.Failure.INVALID_PATH, 1),
                    KotlinFileLengthBaseline.parse(listOf("$path\t400")),
                    path,
                )
            }
    }

    @Test
    fun `limits must be canonical positive integers`() {
        listOf("0", "-1", "0400", "+400", " 400", "2147483648").forEach { limit ->
            assertEquals(
                KotlinFileLengthBaseline.Admission.Rejected(KotlinFileLengthBaseline.Failure.INVALID_LIMIT, 1),
                KotlinFileLengthBaseline.parse(listOf("Source.kt\t$limit")),
                limit,
            )
        }
    }

    @Test
    fun `comments are ignored and only recorded paths receive allowances`() {
        val accepted =
            assertInstanceOf(
                KotlinFileLengthBaseline.Admission.Accepted::class.java,
                KotlinFileLengthBaseline.parse(listOf("# reviewed debt", "", "a/Existing.kt\t500", "a/Small.kt\t100")),
            )
        assertEquals(500, accepted.baseline.limitFor("a/Existing.kt", 400))
        assertEquals(400, accepted.baseline.limitFor("b/Existing.kt", 400))
        assertEquals(400, accepted.baseline.limitFor("a/Small.kt", 400))
    }

    @Test
    fun `extra columns fail at their physical line number`() {
        assertEquals(
            KotlinFileLengthBaseline.Admission.Rejected(KotlinFileLengthBaseline.Failure.INVALID_ROW, 2),
            KotlinFileLengthBaseline.parse(listOf("# heading", "Source.kt\t400\textra")),
        )
    }
}
