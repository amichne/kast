package io.github.amichne.kast.workspace.intellij.read

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.util.ArrayDeque

class DetachedModelTest {
    @Test
    fun `capture retains exact facts in deterministic order under permutation`() {
        val alphaRoots = arrayListOf(
            detachedSourceRootBoundary("alpha/src/test/resources", DetachedSourceRootKind.TEST_RESOURCE),
            detachedSourceRootBoundary("alpha/src/main/kotlin"),
        )
        val alphaClasspath = arrayListOf(
            detachedClasspathBoundary(3, "file:///workspace/kast/.fixture/zeta.jar"),
            detachedClasspathBoundary(2, "file:///workspace/kast/.fixture/alpha.jar"),
        )
        val alpha = detachedModuleBoundary(
            name = "alpha",
            gradleProjectRoot = "${FIXTURE_ROOT.value}/alpha",
            gradleProjectIdentity = ":alpha",
            sourceRoots = alphaRoots,
            sdk = detachedSdkBoundary("Fixture JDK 21", "JavaSDK", "21.0.7"),
            classpath = alphaClasspath,
        )
        val zeta = detachedModuleBoundary(
            name = "zeta",
            gradleProjectRoot = "${FIXTURE_ROOT.value}/zeta",
            gradleProjectIdentity = ":zeta",
            sourceRoots = listOf(
                detachedSourceRootBoundary("zeta/src/main/resources", DetachedSourceRootKind.RESOURCE),
            ),
            sdk = detachedSdkBoundary("Fixture JDK 17", "JavaSDK", "17.0.12"),
            classpath = listOf(
                detachedClasspathBoundary(1, "file:///workspace/kast/.fixture/zeta-runtime.jar"),
            ),
        )
        val rawModules = arrayListOf(zeta, alpha)

        val captured = capturedModel(detachedModelBoundary(modules = rawModules))
        val permuted = capturedModel(
            detachedModelBoundary(
                modules = listOf(
                    alpha.copy(
                        sourceRoots = alphaRoots.reversed(),
                        classpath = alphaClasspath.reversed(),
                    ),
                    zeta,
                ),
            ),
        )

        rawModules.clear()
        alphaRoots.clear()
        alphaClasspath.clear()

        assertEquals(EXPECTED_MODEL, captured.snapshot())
        assertEquals(EXPECTED_MODEL, permuted.snapshot())
    }

    @Test
    fun `captured collections reject mutation through Java collection authority`() {
        val model = capturedModel(detachedModelBoundary())
        val module = model.modules.single()

        assertJavaUnmodifiable(model.modules, module)
        assertJavaUnmodifiable(module.sourceRoots, module.sourceRoots.single())
        assertJavaUnmodifiable(module.classpath, module.classpath.single())
    }

    @Test
    fun `public model surface recursively excludes live and open authority`() {
        val observed = recursivelyObservedSurface(DetachedIdeWorkspaceModel::class.java)

        assertTrue(observed.contains(DetachedIdeWorkspaceModel::class.java.name))
        assertTrue(observed.contains(DetachedIdeModule::class.java.name))
        assertTrue(observed.contains("java.util.List"))
    }

    @Test
    fun `compiled live adapter matches the pinned IDEA 262 contract`() {
        assertEquals(
            emptyList<DetachedModelClassContractFailure>(),
            DetachedModelClassContract.verify(),
        )
    }

    @Test
    fun `classpath URL refinement matches the detached-model contract`() {
        DetachedClasspathUrlRefinementContract.verify()
    }

    private fun capturedModel(boundary: DetachedModelBoundary): DetachedIdeWorkspaceModel =
        assertInstanceOf(
            DetachedModelCapture.Captured::class.java,
            captureDetachedFixture(DetachedModelObservation.Observed(boundary)),
        ).model

    private fun DetachedIdeWorkspaceModel.snapshot(): WorkspaceSnapshot = WorkspaceSnapshot(
        root = canonicalRoot.value,
        compatibility = CompatibilitySnapshot(
            ideBuild = compatibility.ideBuild.value,
            kotlinPluginBuild = compatibility.kotlinPluginBuild.value,
            kastPluginVersion = compatibility.kastPluginVersion.value,
            runtimeProtocolIdentity = compatibility.runtimeProtocolIdentity.value,
            operationRegistryDigest = compatibility.operationRegistryDigest.value,
            wireSchemaDigest = compatibility.wireSchemaDigest.value,
            capabilities = compatibility.capabilities.capabilities.map { capability ->
                capability.operation.id.value
            },
        ),
        modules = modules.map { module ->
            ModuleSnapshot(
                name = module.name.value,
                buildRoot = module.owner.buildRoot.value,
                projectRoot = module.owner.projectRoot.value,
                projectIdentity = module.owner.projectIdentity.value,
                sourceRoots = module.sourceRoots.map { root ->
                    SourceRootSnapshot(root.location.value, root.kind)
                },
                sdk = SdkSnapshot(
                    module.sdk.name.value,
                    module.sdk.type.value,
                    module.sdk.version.value,
                ),
                classpath = module.classpath.map { entry -> entry.url.value },
            )
        },
    )

    @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun <Value> assertJavaUnmodifiable(values: List<Value>, existing: Value) {
        val javaValues = values as java.util.List<Value>
        assertThrows(UnsupportedOperationException::class.java) { javaValues.add(existing) }
        assertThrows(UnsupportedOperationException::class.java) { javaValues.clear() }
    }

    private fun recursivelyObservedSurface(root: Class<*>): Set<String> {
        val observed = linkedSetOf<String>()
        val inspected = linkedSetOf<String>()
        val pending = ArrayDeque<Class<*>>()
        pending += root
        while (pending.isNotEmpty()) {
            val owner = pending.removeFirst()
            if (!inspected.add(owner.name)) continue
            observed += owner.name
            check(owner.typeParameters.isEmpty()) { "open generic authority ${owner.name}" }
            owner.genericSuperclass?.takeUnless { type -> type == Any::class.java }?.let { type ->
                observeType(type, "${owner.name} superclass", observed, pending)
            }
            owner.genericInterfaces.forEach { type -> observeType(type, "${owner.name} interface", observed, pending) }
            owner.declaredFields.filter { field ->
                Modifier.isPublic(field.modifiers) && !field.isSynthetic &&
                    !(field.name == "Companion" && field.type.name.endsWith("\$Companion"))
            }.forEach { field ->
                observeType(field.genericType, field.toGenericString(), observed, pending)
            }
            owner.declaredConstructors.filter { constructor ->
                Modifier.isPublic(constructor.modifiers) && !constructor.isSynthetic
            }.forEach { constructor ->
                constructor.genericParameterTypes.forEach { type ->
                    observeType(type, constructor.toGenericString(), observed, pending)
                }
            }
            owner.declaredMethods.filter { method ->
                Modifier.isPublic(method.modifiers) && !method.isSynthetic && !method.isBridge &&
                    !method.isObjectProtocol()
            }.forEach { method ->
                observeType(method.genericReturnType, method.toGenericString(), observed, pending)
                method.genericParameterTypes.forEach { type ->
                    observeType(type, method.toGenericString(), observed, pending)
                }
            }
        }
        return observed
    }

    private fun Method.isObjectProtocol(): Boolean = when (name) {
        "equals" -> returnType == Boolean::class.javaPrimitiveType &&
            parameterTypes.contentEquals(arrayOf(Any::class.java))
        "hashCode" -> returnType == Int::class.javaPrimitiveType && parameterCount == 0
        "toString" -> returnType == String::class.java && parameterCount == 0
        else -> false
    }

    private fun observeType(
        type: Type,
        context: String,
        observed: MutableSet<String>,
        pending: ArrayDeque<Class<*>>,
    ) {
        when (type) {
            is Class<*> -> observeClass(type, context, observed, pending)
            is ParameterizedType -> {
                observeType(type.rawType, context, observed, pending)
                type.actualTypeArguments.forEach { argument ->
                    observeType(argument, context, observed, pending)
                }
            }
            is GenericArrayType -> observeType(type.genericComponentType, context, observed, pending)
            is WildcardType -> {
                type.lowerBounds.forEach { bound -> observeType(bound, context, observed, pending) }
                type.upperBounds.filterNot { bound -> bound == Any::class.java }.forEach { bound ->
                    observeType(bound, context, observed, pending)
                }
            }
            is TypeVariable<*> -> error("open generic authority $type at $context")
            else -> error("unsupported public type $type at $context")
        }
    }

    private fun observeClass(
        type: Class<*>,
        context: String,
        observed: MutableSet<String>,
        pending: ArrayDeque<Class<*>>,
    ) {
        val name = type.name
        check(name != Any::class.java.name) { "Any authority at $context" }
        check(FORBIDDEN_PREFIXES.none(name::startsWith)) { "live authority $name at $context" }
        check(name !in FORBIDDEN_CALLBACKS) { "callback authority $name at $context" }
        check(!name.startsWith("kotlin.jvm.functions.")) { "callback authority $name at $context" }
        check(!name.startsWith("java.util.function.")) { "callback authority $name at $context" }
        check(!java.util.Collection::class.java.isAssignableFrom(type) || type == List::class.java) {
            "mutable collection authority $name at $context"
        }
        observed += name
        if (name.startsWith("io.github.amichne.kast.") && !type.isEnum) pending += type
    }

    private data class WorkspaceSnapshot(
        val root: String,
        val compatibility: CompatibilitySnapshot,
        val modules: List<ModuleSnapshot>,
    )

    private data class CompatibilitySnapshot(
        val ideBuild: String,
        val kotlinPluginBuild: String,
        val kastPluginVersion: String,
        val runtimeProtocolIdentity: String,
        val operationRegistryDigest: String,
        val wireSchemaDigest: String,
        val capabilities: List<String>,
    )

    private data class ModuleSnapshot(
        val name: String,
        val buildRoot: String,
        val projectRoot: String,
        val projectIdentity: String,
        val sourceRoots: List<SourceRootSnapshot>,
        val sdk: SdkSnapshot,
        val classpath: List<String>,
    )

    private data class SourceRootSnapshot(
        val location: String,
        val kind: DetachedSourceRootKind,
    )

    private data class SdkSnapshot(
        val name: String,
        val type: String,
        val version: String,
    )

    private companion object {
        const val REPORT_PROPERTY = "kast.ide.detached.model.report"
        val FORBIDDEN_PREFIXES = listOf(
            "com.intellij.",
            "org.jetbrains.plugins.gradle.",
            "org.jetbrains.kotlin.",
        )
        val FORBIDDEN_CALLBACKS = setOf(
            "java.lang.Runnable",
            "java.util.concurrent.Callable",
        )

        val EXPECTED_MODEL = WorkspaceSnapshot(
            root = "/workspace/kast",
            compatibility = CompatibilitySnapshot(
                ideBuild = "262.9437.185",
                kotlinPluginBuild = "262.9437.185-IJ",
                kastPluginVersion = "1.2.3",
                runtimeProtocolIdentity = "kast.ide-hosted.runtime.v1",
                operationRegistryDigest = "sha256:" + "1".repeat(64),
                wireSchemaDigest = "sha256:" + "2".repeat(64),
                capabilities = listOf(
                    "index.sync",
                    "topology.build",
                    "symbol.discover",
                    "symbol.inspect",
                    "source.read",
                    "relation.read",
                    "traversal.run",
                    "diagnostic.check",
                    "change.plan",
                    "change.apply",
                    "change.recover",
                ),
            ),
            modules = listOf(
                ModuleSnapshot(
                    name = "alpha",
                    buildRoot = ".",
                    projectRoot = "alpha",
                    projectIdentity = ":alpha",
                    sourceRoots = listOf(
                        SourceRootSnapshot("alpha/src/main/kotlin", DetachedSourceRootKind.PRODUCTION),
                        SourceRootSnapshot(
                            "alpha/src/test/resources",
                            DetachedSourceRootKind.TEST_RESOURCE,
                        ),
                    ),
                    sdk = SdkSnapshot("Fixture JDK 21", "JavaSDK", "21.0.7"),
                    classpath = listOf(
                        "file:///workspace/kast/.fixture/alpha.jar",
                        "file:///workspace/kast/.fixture/zeta.jar",
                    ),
                ),
                ModuleSnapshot(
                    name = "zeta",
                    buildRoot = ".",
                    projectRoot = "zeta",
                    projectIdentity = ":zeta",
                    sourceRoots = listOf(
                        SourceRootSnapshot(
                            "zeta/src/main/resources",
                            DetachedSourceRootKind.RESOURCE,
                        ),
                    ),
                    sdk = SdkSnapshot("Fixture JDK 17", "JavaSDK", "17.0.12"),
                    classpath = listOf(
                        "file:///workspace/kast/.fixture/zeta-runtime.jar",
                    ),
                ),
            ),
        )

    }
}
