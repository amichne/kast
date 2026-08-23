package support.pr633

import org.objectweb.asm.Opcodes

internal fun classAccessNames(access: AdmittedJvmClassAccess): List<String> = buildList {
    add(access.visibility.manifestName)
    if (access.flags and Opcodes.ACC_STATIC != 0) add("static")
    if (access.flags and Opcodes.ACC_INTERFACE != 0) add("interface")
    if (access.flags and Opcodes.ACC_ABSTRACT != 0) add("abstract")
    if (access.flags and Opcodes.ACC_FINAL != 0) add("final")
    if (access.flags and Opcodes.ACC_ENUM != 0) add("enum")
    if (access.flags and Opcodes.ACC_ANNOTATION != 0) add("annotation")
    if (access.flags and Opcodes.ACC_RECORD != 0) add("record")
}

internal fun fieldAccessNames(access: AdmittedJvmMemberAccess): List<String> = buildList {
    add(access.visibility.manifestName)
    if (access.flags and Opcodes.ACC_STATIC != 0) add("static")
    if (access.flags and Opcodes.ACC_FINAL != 0) add("final")
    if (access.flags and Opcodes.ACC_VOLATILE != 0) add("volatile")
    if (access.flags and Opcodes.ACC_TRANSIENT != 0) add("transient")
    if (access.flags and Opcodes.ACC_ENUM != 0) add("enum")
}

internal fun methodAccessNames(access: AdmittedJvmMemberAccess): List<String> = buildList {
    add(access.visibility.manifestName)
    if (access.flags and Opcodes.ACC_STATIC != 0) add("static")
    if (access.flags and Opcodes.ACC_FINAL != 0) add("final")
    if (access.flags and Opcodes.ACC_SYNCHRONIZED != 0) add("synchronized")
    if (access.flags and Opcodes.ACC_BRIDGE != 0) add("bridge")
    if (access.flags and Opcodes.ACC_VARARGS != 0) add("varargs")
    if (access.flags and Opcodes.ACC_NATIVE != 0) add("native")
    if (access.flags and Opcodes.ACC_ABSTRACT != 0) add("abstract")
    if (access.flags and Opcodes.ACC_STRICT != 0) add("strict")
}

internal sealed interface JvmGenericSignature {
    data object Absent : JvmGenericSignature
    data class Present(val value: String) : JvmGenericSignature

    companion object {
        /**
         * Proof transition: `String? -> JvmGenericSignature`.
         *
         * Converts ASM nullability into an explicit absent-or-present signature state. Raw nullable
         * signature text is extracted only at the ASM class/member visitor boundary.
         */
        fun fromBoundary(value: String?): JvmGenericSignature = when (value) {
            null -> Absent
            else -> Present(value)
        }
    }
}

internal sealed interface JvmFieldConstant {
    val manifestValue: String

    data object Absent : JvmFieldConstant {
        override val manifestValue: String = ""
    }
    data class IntegerValue(val value: Int) : JvmFieldConstant {
        override val manifestValue: String = "int:$value"
    }
    data class LongValue(val value: Long) : JvmFieldConstant {
        override val manifestValue: String = "long:$value"
    }
    data class FloatValue(val value: Float) : JvmFieldConstant {
        override val manifestValue: String =
            "float-bits:${java.lang.Float.floatToRawIntBits(value).toUInt().toString(16)}"
    }
    data class DoubleValue(val value: Double) : JvmFieldConstant {
        override val manifestValue: String =
            "double-bits:${java.lang.Double.doubleToRawLongBits(value).toULong().toString(16)}"
    }
    data class StringValue(val value: String) : JvmFieldConstant {
        override val manifestValue: String =
            "utf8-base64:${java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))}"
    }

    companion object {
        /**
         * Proof transition: `Any? -> JvmFieldConstant` at the ASM constant-value boundary.
         *
         * Establishes a type-tagged deterministic representation for every JVM ConstantValue
         * kind. Absence remains explicit. Other runtime types indicate malformed classfile input.
         */
        fun fromBoundary(value: Any?): JvmFieldConstant = when (value) {
            null -> Absent
            is Int -> IntegerValue(value)
            is Long -> LongValue(value)
            is Float -> FloatValue(value)
            is Double -> DoubleValue(value)
            is String -> StringValue(value)
            else -> error("unsupported JVM ConstantValue type: ${value::class.qualifiedName}")
        }
    }
}

internal sealed interface JvmSuperclass {
    data object Absent : JvmSuperclass
    data class Present(val name: JvmClassName) : JvmSuperclass

    companion object {
        /**
         * Proof transition: `String? -> JvmSuperclass`.
         *
         * Converts ASM nullability into an explicit root-or-named superclass state. Raw nullable
         * names are extracted only at the ASM class visitor boundary.
         */
        fun fromBoundary(value: String?): JvmSuperclass = when (value) {
            null -> Absent
            else -> Present(JvmClassName.fromClassfile(value))
        }
    }
}

internal fun StringBuilder.appendSignature(signature: JvmGenericSignature) {
    when (signature) {
        JvmGenericSignature.Absent -> Unit
        is JvmGenericSignature.Present -> append(" signature=${signature.value}")
    }
}

internal fun StringBuilder.appendFieldConstant(constant: JvmFieldConstant) {
    when (constant) {
        JvmFieldConstant.Absent -> Unit
        else -> append(" constant=${constant.manifestValue}")
    }
}
