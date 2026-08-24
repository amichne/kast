package support.pr633

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor

internal class CompiledTopologyContractApi private constructor(
    val entries: List<TopologyContractAbiEntry>,
    val forbiddenClasses: List<JvmClassName>,
    val forbiddenMethods: List<JvmMethodIdentity>,
) {
    companion object {
        /**
         * Proof transition: `(TopologyContractApiPolicy, List<ByteArray>) ->
         * CompiledTopologyContractApiProjection`.
         *
         * Establishes a normalized logical JVM API projection and typed zero-budget findings from
         * every supplied classfile. [CompiledTopologyContractApiProjection.EmptyClassfiles] is the
         * closed expected failure. Malformed classfiles are exceptional build corruption. Raw bytes
         * are extracted only at the Gradle file-input boundary; construction remains here.
         */
        fun project(
            policy: TopologyContractApiPolicy,
            classBytes: List<ByteArray>,
        ): CompiledTopologyContractApiProjection {
            if (classBytes.isEmpty()) return CompiledTopologyContractApiProjection.EmptyClassfiles
            val entries = mutableListOf<TopologyContractAbiEntry>()
            val forbiddenClasses = mutableListOf<JvmClassName>()
            val forbiddenMethods = mutableListOf<JvmMethodIdentity>()
            val classNames = mutableListOf<JvmClassName>()
            classBytes.forEach { bytes ->
                val collector = TopologyContractApiCollector(policy)
                ClassReader(bytes).accept(
                    collector,
                    ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
                )
                entries += collector.entries
                forbiddenClasses += collector.forbiddenClasses
                forbiddenMethods += collector.forbiddenMethods
                classNames += collector.visitedClass
            }
            val duplicates = classNames.groupingBy { it }.eachCount()
                .filterValues { count -> count > 1 }
                .keys
                .sortedBy(JvmClassName::value)
            if (duplicates.isNotEmpty()) {
                return CompiledTopologyContractApiProjection.DuplicateClasses(
                    duplicates.first(),
                    duplicates.drop(1),
                )
            }
            return CompiledTopologyContractApiProjection.Projected(
                CompiledTopologyContractApi(
                    entries = entries.distinct().sortedBy(TopologyContractAbiEntry::value),
                    forbiddenClasses = forbiddenClasses.distinct().sortedBy(JvmClassName::value),
                    forbiddenMethods = forbiddenMethods.distinct().sortedBy(JvmMethodIdentity::display),
                ),
            )
        }
    }
}

internal sealed interface CompiledTopologyContractApiProjection {
    data object EmptyClassfiles : CompiledTopologyContractApiProjection
    data class DuplicateClasses(
        val firstClass: JvmClassName,
        val additionalClasses: List<JvmClassName>,
    ) : CompiledTopologyContractApiProjection {
        val classes: List<JvmClassName> = listOf(firstClass) + additionalClasses
    }
    data class Projected(val api: CompiledTopologyContractApi) : CompiledTopologyContractApiProjection
}

private class TopologyContractApiCollector(
    private val policy: TopologyContractApiPolicy,
) : ClassVisitor(Opcodes.ASM9) {
    val entries = mutableListOf<TopologyContractAbiEntry>()
    val forbiddenClasses = mutableListOf<JvmClassName>()
    val forbiddenMethods = mutableListOf<JvmMethodIdentity>()
    private var classState: JvmClassProjectionState = JvmClassProjectionState.NotVisited
    private val permittedSubclasses = mutableListOf<JvmClassName>()
    val visitedClass: JvmClassName
        get() = className()

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        val className = JvmClassName.fromClassfile(name)
        classState = admitJvmClass(
            JvmClassHeader(
                name = className,
                headerAccess = access,
                nestedAccess = JvmNestedClassAccess.HeaderOnly,
                signature = JvmGenericSignature.fromBoundary(signature),
                superclass = JvmSuperclass.fromBoundary(superName),
                interfaces = interfaces.orEmpty().map(JvmClassName::fromClassfile),
            ),
        )
    }

    override fun visitInnerClass(
        name: String,
        outerName: String?,
        innerName: String?,
        access: Int,
    ) {
        val header = visitedHeader()
        if (name == header.name.value) {
            classState = admitJvmClass(
                header.copy(nestedAccess = JvmNestedClassAccess.SelfEntry(access)),
            )
        }
    }

    override fun visitPermittedSubclass(permittedSubclass: String) {
        permittedSubclasses += JvmClassName.fromClassfile(permittedSubclass)
    }

    override fun visitEnd() {
        when (val state = classState) {
            JvmClassProjectionState.NotVisited -> error("ASM completed a class without its header")
            is JvmClassProjectionState.Excluded -> Unit
            is JvmClassProjectionState.Included -> {
                if (state.header.name.simpleNames.any { it in policy.forbiddenClassSimpleNames }) {
                    forbiddenClasses += state.header.name
                }
                entries += classEntry(state, permittedSubclasses)
            }
        }
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?,
    ): FieldVisitor? {
        val owner = includedOwner()
        when (val admission = admitJvmMemberAccess(access)) {
            JvmMemberAccessAdmission.Excluded -> Unit
            is JvmMemberAccessAdmission.Included -> if (owner is IncludedOwner.Present) {
                entries += TopologyContractAbiEntry(
                    buildString {
                        append("field ${owner.name.value}#$name:$descriptor")
                        append(" access=${fieldAccessNames(admission.access).joinToString(",")}")
                        appendFieldConstant(JvmFieldConstant.fromBoundary(value))
                        appendSignature(JvmGenericSignature.fromBoundary(signature))
                    },
                )
            }
        }
        return null
    }

    override fun visitRecordComponent(
        name: String,
        descriptor: String,
        signature: String?,
    ): RecordComponentVisitor? {
        when (val owner = includedOwner()) {
            IncludedOwner.Absent -> Unit
            is IncludedOwner.Present -> entries += TopologyContractAbiEntry(
                buildString {
                    append("record-component ${owner.name.value}#$name:$descriptor")
                    appendSignature(JvmGenericSignature.fromBoundary(signature))
                },
            )
        }
        return null
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        val methodName = JvmMethodName.fromClassfile(name)
        val memberAccess = admitJvmMemberAccess(access)
        val owner = includedOwner()
        if (
            owner is IncludedOwner.Present &&
            memberAccess is JvmMemberAccessAdmission.Included &&
            methodName in policy.forbiddenMethodNames
        ) {
            forbiddenMethods += JvmMethodIdentity(className(), methodName, descriptor)
        }
        val admission = admitJvmMethod(JvmMethodHeader(memberAccess, methodName))
        if (owner is IncludedOwner.Present && admission is JvmMethodProjectionAdmission.Included) {
            entries += TopologyContractAbiEntry(
                buildString {
                    append("method ${owner.name.value}#${methodName.value}$descriptor")
                    append(" access=${methodAccessNames(admission.access).joinToString(",")}")
                    appendSignature(JvmGenericSignature.fromBoundary(signature))
                    val thrownTypes = exceptions.orEmpty().sorted()
                    if (thrownTypes.isNotEmpty()) append(" throws=${thrownTypes.joinToString(",")}")
                },
            )
        }
        return null
    }

    private fun className(): JvmClassName = when (val state = classState) {
        JvmClassProjectionState.NotVisited -> error("ASM visited a member before its class header")
        is JvmClassProjectionState.Excluded -> state.header.name
        is JvmClassProjectionState.Included -> state.header.name
    }

    private fun visitedHeader(): JvmClassHeader = when (val state = classState) {
        JvmClassProjectionState.NotVisited -> error("ASM visited metadata before its class header")
        is JvmClassProjectionState.Excluded -> state.header
        is JvmClassProjectionState.Included -> state.header
    }

    private fun includedOwner(): IncludedOwner = when (val state = classState) {
        JvmClassProjectionState.NotVisited -> error("ASM visited a member before its class header")
        is JvmClassProjectionState.Excluded -> IncludedOwner.Absent
        is JvmClassProjectionState.Included -> IncludedOwner.Present(state.header.name)
    }
}

private sealed interface IncludedOwner {
    data object Absent : IncludedOwner
    data class Present(val name: JvmClassName) : IncludedOwner
}

private data class JvmClassHeader(
    val name: JvmClassName,
    val headerAccess: Int,
    val nestedAccess: JvmNestedClassAccess,
    val signature: JvmGenericSignature,
    val superclass: JvmSuperclass,
    val interfaces: List<JvmClassName>,
) {
    val effectiveAccess: Int
        get() = when (val nested = nestedAccess) {
            JvmNestedClassAccess.HeaderOnly -> headerAccess
            is JvmNestedClassAccess.SelfEntry ->
                nested.flags or (headerAccess and Opcodes.ACC_RECORD)
        }
}

private sealed interface JvmNestedClassAccess {
    data object HeaderOnly : JvmNestedClassAccess
    data class SelfEntry(val flags: Int) : JvmNestedClassAccess
}

private sealed interface JvmClassProjectionState {
    data object NotVisited : JvmClassProjectionState
    data class Excluded(val header: JvmClassHeader) : JvmClassProjectionState
    data class Included(
        val header: JvmClassHeader,
        val access: AdmittedJvmClassAccess,
    ) : JvmClassProjectionState
}

internal data class AdmittedJvmClassAccess(val flags: Int, val visibility: JvmApiVisibility)

internal enum class JvmApiVisibility(val manifestName: String) {
    PUBLIC("public"),
    PROTECTED("protected"),
}

/**
 * Proof transition: `JvmClassHeader -> JvmClassProjectionState`.
 *
 * An included state proves public-or-protected visibility and absence of synthetic/inlined
 * implementation markers. Exclusion is a closed state. Raw access flags enter at the ASM visitor.
 */
private fun admitJvmClass(header: JvmClassHeader): JvmClassProjectionState = when {
    header.effectiveAccess and Opcodes.ACC_SYNTHETIC != 0 ->
        JvmClassProjectionState.Excluded(header)
    "\$\$inlined\$" in header.name.value -> JvmClassProjectionState.Excluded(header)
    header.effectiveAccess and Opcodes.ACC_PRIVATE != 0 -> JvmClassProjectionState.Excluded(header)
    header.effectiveAccess and Opcodes.ACC_PUBLIC != 0 -> JvmClassProjectionState.Included(
        header,
        AdmittedJvmClassAccess(header.effectiveAccess, JvmApiVisibility.PUBLIC),
    )
    header.effectiveAccess and Opcodes.ACC_PROTECTED != 0 -> JvmClassProjectionState.Included(
        header,
        AdmittedJvmClassAccess(header.effectiveAccess, JvmApiVisibility.PROTECTED),
    )
    else -> JvmClassProjectionState.Excluded(header)
}

private sealed interface JvmMemberAccessAdmission {
    data object Excluded : JvmMemberAccessAdmission
    data class Included(val access: AdmittedJvmMemberAccess) : JvmMemberAccessAdmission
}

internal data class AdmittedJvmMemberAccess(val flags: Int, val visibility: JvmApiVisibility)

/**
 * Proof transition: `Int -> JvmMemberAccessAdmission`.
 *
 * An included state proves JVM public-or-protected visibility and absence of the synthetic flag.
 * Exclusion is closed and raw access flags are extracted only at the ASM member visitor boundary.
 */
private fun admitJvmMemberAccess(access: Int): JvmMemberAccessAdmission = when {
    access and Opcodes.ACC_SYNTHETIC != 0 -> JvmMemberAccessAdmission.Excluded
    access and Opcodes.ACC_PUBLIC != 0 -> JvmMemberAccessAdmission.Included(
        AdmittedJvmMemberAccess(access, JvmApiVisibility.PUBLIC),
    )
    access and Opcodes.ACC_PROTECTED != 0 -> JvmMemberAccessAdmission.Included(
        AdmittedJvmMemberAccess(access, JvmApiVisibility.PROTECTED),
    )
    else -> JvmMemberAccessAdmission.Excluded
}

private data class JvmMethodHeader(
    val access: JvmMemberAccessAdmission,
    val name: JvmMethodName,
)

private sealed interface JvmMethodProjectionAdmission {
    data object Excluded : JvmMethodProjectionAdmission
    data class Included(val access: AdmittedJvmMemberAccess) : JvmMethodProjectionAdmission
}

/**
 * Proof transition: `JvmMethodHeader -> JvmMethodProjectionAdmission`.
 *
 * Inclusion preserves the member admission proof and establishes a name other than the JVM class
 * initializer. Exclusion is closed. Raw names enter at ASM.
 */
private fun admitJvmMethod(header: JvmMethodHeader): JvmMethodProjectionAdmission =
    when (val access = header.access) {
        JvmMemberAccessAdmission.Excluded -> JvmMethodProjectionAdmission.Excluded
        is JvmMemberAccessAdmission.Included -> when {
            header.name.value == "<clinit>" -> JvmMethodProjectionAdmission.Excluded
            else -> JvmMethodProjectionAdmission.Included(access.access)
        }
}

private fun classEntry(
    state: JvmClassProjectionState.Included,
    permittedSubclasses: List<JvmClassName>,
): TopologyContractAbiEntry = TopologyContractAbiEntry(
    buildString {
        append(
            "class ${state.header.name.value} " +
                "access=${classAccessNames(state.access).joinToString(",")}",
        )
        appendSignature(state.header.signature)
        append(" super=")
        append(
            when (val superclass = state.header.superclass) {
                JvmSuperclass.Absent -> "-"
                is JvmSuperclass.Present -> superclass.name.value
            },
        )
        append(" interfaces=")
        append(state.header.interfaces.sortedBy(JvmClassName::value).joinToString(",") { it.value })
        append(" permits=${permittedSubclasses.sortedBy(JvmClassName::value).joinToString(",") { it.value }}")
    },
)
