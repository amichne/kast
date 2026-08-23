package support.pr633

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes
import java.nio.file.Path

class VerifyTopologyContractApiTaskTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val fixture: TopologyContractApiTestFixture
        get() = TopologyContractApiTestFixture(temporaryDirectory)

    @Test
    fun `public methods on Kotlin file facades are projected`() {
        fixture.writeClass(
            owner = "fixture/PublicApiKt",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            methods = listOf(PublicMethod("topLevelApi")),
        )

        assertDoesNotThrow {
            fixture.configuredTask(
                "class fixture/PublicApiKt access=public,final super=java/lang/Object interfaces= permits=",
                "method fixture/PublicApiKt#topLevelApi()V access=public,static,native",
            ).verify()
        }
    }

    @Test
    fun `class generic signatures are projected`() {
        fixture.writeClass(
            owner = "fixture/GenericApi",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            signature = "<T:Ljava/lang/Object;>Ljava/lang/Object;",
        )

        assertDoesNotThrow {
            fixture.configuredTask(
                "class fixture/GenericApi access=public,abstract " +
                    "signature=<T:Ljava/lang/Object;>Ljava/lang/Object; " +
                    "super=java/lang/Object interfaces= permits=",
            ).verify()
        }
    }

    @Test
    fun `all user-visible methods including conventional data names are projected`() {
        fixture.writeClass(
            owner = "fixture/PublicApi",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            methods = listOf(
                PublicMethod("copyGraph"),
                PublicMethod("componentTopology"),
                PublicMethod("copy"),
                PublicMethod("component1"),
                PublicMethod("equals"),
            ),
        )

        assertDoesNotThrow {
            fixture.configuredTask(
                "class fixture/PublicApi access=public,final super=java/lang/Object interfaces= permits=",
                "method fixture/PublicApi#component1()V access=public,static,native",
                "method fixture/PublicApi#componentTopology()V access=public,static,native",
                "method fixture/PublicApi#copy()V access=public,static,native",
                "method fixture/PublicApi#copyGraph()V access=public,static,native",
                "method fixture/PublicApi#equals()V access=public,static,native",
            ).verify()
        }
    }

    @Test
    fun `public method access flags are projected`() {
        fixture.writeClass(
            owner = "fixture/PublicApi",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            methods = listOf(
                PublicMethod(
                    name = "staticNativeApi",
                    access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE,
                ),
            ),
        )

        assertDoesNotThrow {
            fixture.configuredTask(
                "class fixture/PublicApi access=public,final super=java/lang/Object interfaces= permits=",
                "method fixture/PublicApi#staticNativeApi()V access=public,static,native",
            ).verify()
        }
    }

    @Test
    fun `public field access flags are projected`() {
        fixture.writeClass(
            owner = "fixture/PublicApi",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            fields = listOf(
                PublicField(
                    name = "CONSTANT",
                    access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                ),
            ),
        )

        assertDoesNotThrow {
            fixture.configuredTask(
                "class fixture/PublicApi access=public,final super=java/lang/Object interfaces= permits=",
                "field fixture/PublicApi#CONSTANT:I access=public,static,final",
            ).verify()
        }
    }

    @Test
    fun `synthetic public fields are excluded`() {
        fixture.writeClass(
            owner = "fixture/PublicApi",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            fields = listOf(
                PublicField(
                    name = "syntheticHelper",
                    access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
                ),
            ),
        )

        assertDoesNotThrow {
            fixture.configuredTask(
                "class fixture/PublicApi access=public,final super=java/lang/Object interfaces= permits=",
            ).verify()
        }
    }

    @Test
    fun `empty zero-budget policy is rejected`() {
        fixture.writeClass(
            owner = "fixture/PublicApi",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
        )

        assertThrows(IllegalStateException::class.java) {
            fixture.configuredTask(
                "class fixture/PublicApi access=public,final super=java/lang/Object interfaces= permits=",
                forbiddenClasses = emptySet(),
                forbiddenMethods = emptySet(),
            ).verify()
        }
    }

    @Test
    fun `empty compiled projection is rejected`() {
        assertThrows(IllegalStateException::class.java) {
            fixture.configuredTask().verify()
        }
    }

    @Test
    fun `empty checked manifest is rejected`() {
        assertTrue(
            CheckedTopologyContractAbi.parse(emptyList()) ==
                CheckedTopologyContractAbiAdmission.EmptyManifest,
        )
    }

    @Test
    fun `duplicate compiled class identity is rejected`() {
        fixture.writeClass(
            owner = "fixture/PublicApi",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
        )
        val duplicateRoot = temporaryDirectory.resolve("duplicate-classes")
        fixture.writeClass(
            owner = "fixture/PublicApi",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            root = duplicateRoot,
        )

        assertThrows(IllegalStateException::class.java) {
            fixture.configuredTask(
                "class fixture/PublicApi access=public,final super=java/lang/Object interfaces= permits=",
                classRoots = listOf(fixture.classesDirectory(), duplicateRoot),
            ).verify()
        }
    }

    @Test
    fun `deferred topology path and query API shapes are rejected`() {
        fixture.writeClass(
            owner = "fixture/TopologyPath",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            methods = listOf(PublicMethod("query")),
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            fixture.configuredTask(
                "class fixture/TopologyPath access=public,final super=java/lang/Object interfaces= permits=",
                "method fixture/TopologyPath#query()V access=public,static,native",
                forbiddenClasses = setOf("TopologyPath", "TopologyQuery"),
                forbiddenMethods = setOf("path", "query"),
            ).verify()
        }
        assertTrue(failure.message.orEmpty().contains("TopologyPath"))
        assertTrue(failure.message.orEmpty().contains("#query"))
    }

    @Test
    fun `forbidden API compiled only to the Java output is rejected`() {
        val javaClasses = temporaryDirectory.resolve("classes/java/main")
        fixture.compileJava(
            javaClasses,
            """
            package fixture;

            public final class TopologyQuery {
                public static native void query();
            }
            """.trimIndent(),
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            fixture.configuredTask(
                "class fixture/TopologyQuery access=public,final super=java/lang/Object interfaces= permits=",
                "method fixture/TopologyQuery#query()V access=public,static,native",
                forbiddenClasses = setOf("TopologyQuery"),
                forbiddenMethods = setOf("query"),
                classRoots = listOf(fixture.classesDirectory(), javaClasses),
            ).verify()
        }

        assertTrue(failure.message.orEmpty().contains("TopologyQuery"))
        assertTrue(failure.message.orEmpty().contains("#query"))
    }

    @Test
    fun `sealed permitted subclasses are projected`() {
        val owner = "fixture/SealedApi"
        val expected =
            "class $owner access=public,interface,abstract super=java/lang/Object interfaces= " +
                "permits=fixture/First,fixture/Second"
        fixture.writeClass(
            owner = owner,
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            permittedSubclasses = listOf("fixture/Second", "fixture/First"),
        )

        assertDoesNotThrow { fixture.configuredTask(expected).verify() }

        fixture.writeClass(
            owner = owner,
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
        )
        assertThrows(IllegalStateException::class.java) { fixture.configuredTask(expected).verify() }
    }

    @Test
    fun `record metadata is projected`() {
        fixture.writeClass(
            owner = "fixture/PublicRecord",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_RECORD,
            superclass = "java/lang/Record",
            recordComponents = listOf(
                PublicRecordComponent(
                    name = "values",
                    descriptor = "Ljava/util/List;",
                    signature = "Ljava/util/List<Ljava/lang/String;>;",
                ),
            ),
        )

        assertDoesNotThrow {
            fixture.configuredTask(
                "class fixture/PublicRecord access=public,final,record " +
                    "super=java/lang/Record interfaces= permits=",
                "record-component fixture/PublicRecord#values:Ljava/util/List; " +
                    "signature=Ljava/util/List<Ljava/lang/String;>;",
            ).verify()
        }
    }

    @Test
    fun `nested class access comes from its self InnerClasses entry`() {
        val owner = "fixture/Outer\$ProtectedApi"
        val expected =
            "class $owner access=protected,static,final " +
                "super=java/lang/Object interfaces= permits="
        fixture.writeClass(
            owner = owner,
            access = Opcodes.ACC_SUPER or Opcodes.ACC_FINAL,
            innerClass = PublicInnerClass(
                outerName = "fixture/Outer",
                innerName = "ProtectedApi",
                access = Opcodes.ACC_PROTECTED or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            ),
        )

        assertDoesNotThrow { fixture.configuredTask(expected).verify() }

        fixture.writeClass(
            owner = owner,
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER or Opcodes.ACC_FINAL,
            innerClass = PublicInnerClass(
                outerName = "fixture/Outer",
                innerName = "ProtectedApi",
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            ),
        )
        assertThrows(IllegalStateException::class.java) { fixture.configuredTask(expected).verify() }
    }

    @Test
    fun `nested forbidden class segments are rejected`() {
        val owner = "fixture/AllowedOuter\$TopologyGraph"
        fixture.writeClass(
            owner = owner,
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            fixture.configuredTask(
                "class $owner access=public,final super=java/lang/Object interfaces= permits=",
                forbiddenClasses = setOf("TopologyGraph"),
            ).verify()
        }
        assertTrue(failure.message.orEmpty().contains(owner))
    }

    @Test
    fun `public field constant values are projected`() {
        val classEntry =
            "class fixture/PublicApi access=public,final super=java/lang/Object interfaces= permits="
        val fieldEntry =
            "field fixture/PublicApi#ANSWER:I access=public,static,final constant=int:42"
        fixture.writeClass(
            owner = "fixture/PublicApi",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            fields = listOf(
                PublicField(
                    name = "ANSWER",
                    access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                    constant = 42,
                ),
            ),
        )

        assertDoesNotThrow { fixture.configuredTask(classEntry, fieldEntry).verify() }

        fixture.writeClass(
            owner = "fixture/PublicApi",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            fields = listOf(
                PublicField(
                    name = "ANSWER",
                    access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                    constant = 43,
                ),
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            fixture.configuredTask(classEntry, fieldEntry).verify()
        }
    }

    @Test
    fun `non-canonical topology policy names are rejected`() {
        fixture.writeClass(
            owner = "fixture/PublicApi",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
        )

        assertThrows(IllegalStateException::class.java) {
            fixture.configuredTask(
                "class fixture/PublicApi access=public,final super=java/lang/Object interfaces= permits=",
                forbiddenClasses = setOf(" ForbiddenGraph"),
                forbiddenMethods = setOf(" "),
            ).verify()
        }
    }

}
