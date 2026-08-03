package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.docs.DocField
import java.util.Collections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ReplacementDeclarationSignature {
    val name: String
    val receiverType: String?
    val returnType: String
}

@Serializable
@SerialName("function")
class ReplacementFunctionSignature private constructor(
    @DocField(description = "Compiler-provided function name.")
    override val name: String,
    @DocField(description = "Canonical receiver type, or null for a function without a receiver.")
    override val receiverType: String?,
    @DocField(description = "Canonical context receiver types in declaration order.")
    @SerialName("contextReceiverTypes")
    private val storedContextReceiverTypes: List<String>,
    @DocField(description = "Compiler-provided type parameter signatures in declaration order.")
    @SerialName("typeParameters")
    private val storedTypeParameters: List<ReplacementTypeParameterSignature>,
    @DocField(description = "Compiler-provided value parameter signatures in declaration order.")
    @SerialName("valueParameters")
    private val storedValueParameters: List<ReplacementValueParameterSignature>,
    @DocField(description = "Canonical compiler return type.")
    override val returnType: String,
    @DocField(description = "Compiler-provided function visibility.")
    val visibility: ReplacementVisibility,
    @DocField(description = "Compiler-provided function modality.")
    val modality: ReplacementModality,
    @DocField(description = "Whether compiler parameter names are stable.")
    val hasStableParameterNames: Boolean,
    @DocField(description = "Whether the function has the suspend modifier.")
    val suspend: Boolean,
    @DocField(description = "Whether the function has the operator modifier.")
    val operator: Boolean,
    @DocField(description = "Whether the function has the inline modifier.")
    val inline: Boolean,
    @DocField(description = "Whether the function overrides another declaration.")
    val override: Boolean,
    @DocField(description = "Whether the function has the infix modifier.")
    val infix: Boolean,
    @DocField(description = "Whether the compiler exposes the function as static.")
    val static: Boolean,
    @DocField(description = "Whether the function has the tailrec modifier.")
    val tailrec: Boolean,
    @DocField(description = "Whether the function has the external modifier.")
    val external: Boolean,
    @DocField(description = "Whether the function has the expect modifier.")
    val expect: Boolean,
    @DocField(description = "Whether the function has the actual modifier.")
    val actual: Boolean,
) : ReplacementDeclarationSignature {
    val contextReceiverTypes: List<String>
        get() = Collections.unmodifiableList(storedContextReceiverTypes)
    val typeParameters: List<ReplacementTypeParameterSignature>
        get() = Collections.unmodifiableList(storedTypeParameters)
    val valueParameters: List<ReplacementValueParameterSignature>
        get() = Collections.unmodifiableList(storedValueParameters)

    init {
        require(name.isNotBlank()) { "Replacement function name must not be blank" }
        require(receiverType == null || receiverType.isNotBlank()) {
            "Replacement function receiver type must be null or non-blank"
        }
        require(storedContextReceiverTypes.all(String::isNotBlank)) {
            "Replacement function context receiver types must not be blank"
        }
        require(returnType.isNotBlank()) { "Replacement function return type must not be blank" }
    }

    override fun equals(other: Any?): Boolean = other is ReplacementFunctionSignature &&
        name == other.name &&
        receiverType == other.receiverType &&
        storedContextReceiverTypes == other.storedContextReceiverTypes &&
        storedTypeParameters == other.storedTypeParameters &&
        storedValueParameters == other.storedValueParameters &&
        returnType == other.returnType &&
        visibility == other.visibility &&
        modality == other.modality &&
        hasStableParameterNames == other.hasStableParameterNames &&
        suspend == other.suspend &&
        operator == other.operator &&
        inline == other.inline &&
        override == other.override &&
        infix == other.infix &&
        static == other.static &&
        tailrec == other.tailrec &&
        external == other.external &&
        expect == other.expect &&
        actual == other.actual

    override fun hashCode(): Int = listOf(
        name,
        receiverType,
        storedContextReceiverTypes,
        storedTypeParameters,
        storedValueParameters,
        returnType,
        visibility,
        modality,
        hasStableParameterNames,
        suspend,
        operator,
        inline,
        override,
        infix,
        static,
        tailrec,
        external,
        expect,
        actual,
    ).hashCode()

    override fun toString(): String =
        "ReplacementFunctionSignature(name=$name, receiverType=$receiverType, " +
            "contextReceiverTypes=$storedContextReceiverTypes, typeParameters=$storedTypeParameters, " +
            "valueParameters=$storedValueParameters, returnType=$returnType, visibility=$visibility, " +
            "modality=$modality, hasStableParameterNames=$hasStableParameterNames, suspend=$suspend, " +
            "operator=$operator, inline=$inline, override=$override, infix=$infix, static=$static, " +
            "tailrec=$tailrec, external=$external, expect=$expect, actual=$actual)"

    companion object {
        fun of(
            name: String,
            receiverType: String?,
            contextReceiverTypes: List<String>,
            typeParameters: List<ReplacementTypeParameterSignature>,
            valueParameters: List<ReplacementValueParameterSignature>,
            returnType: String,
            visibility: ReplacementVisibility,
            modality: ReplacementModality,
            hasStableParameterNames: Boolean,
            suspend: Boolean,
            operator: Boolean,
            inline: Boolean,
            override: Boolean,
            infix: Boolean,
            static: Boolean,
            tailrec: Boolean,
            external: Boolean,
            expect: Boolean,
            actual: Boolean,
        ): ReplacementFunctionSignature = ReplacementFunctionSignature(
            name = name,
            receiverType = receiverType,
            storedContextReceiverTypes = contextReceiverTypes.toList(),
            storedTypeParameters = typeParameters.toList(),
            storedValueParameters = valueParameters.toList(),
            returnType = returnType,
            visibility = visibility,
            modality = modality,
            hasStableParameterNames = hasStableParameterNames,
            suspend = suspend,
            operator = operator,
            inline = inline,
            override = override,
            infix = infix,
            static = static,
            tailrec = tailrec,
            external = external,
            expect = expect,
            actual = actual,
        )
    }
}

@Serializable
@SerialName("property")
class ReplacementPropertySignature private constructor(
    @DocField(description = "Compiler-provided property name.")
    override val name: String,
    @DocField(description = "Canonical receiver type, or null for a property without a receiver.")
    override val receiverType: String?,
    @DocField(description = "Canonical context receiver types in declaration order.")
    @SerialName("contextReceiverTypes")
    private val storedContextReceiverTypes: List<String>,
    @DocField(description = "Compiler-provided type parameter signatures in declaration order.")
    @SerialName("typeParameters")
    private val storedTypeParameters: List<ReplacementTypeParameterSignature>,
    @DocField(description = "Canonical compiler property type.")
    override val returnType: String,
    @DocField(description = "Compiler-provided property visibility.")
    val visibility: ReplacementVisibility,
    @DocField(description = "Compiler-provided property modality.")
    val modality: ReplacementModality,
    @DocField(description = "Compiler-provided getter visibility.")
    val getterVisibility: ReplacementVisibility,
    @DocField(description = "Compiler-provided setter visibility, or null when no setter exists.")
    val setterVisibility: ReplacementVisibility?,
    @DocField(description = "Whether the property has a getter.")
    val hasGetter: Boolean,
    @DocField(description = "Whether the property has a setter.")
    val hasSetter: Boolean,
    @DocField(description = "Whether the property has a backing field.")
    val hasBackingField: Boolean,
    @DocField(description = "Whether the property is read-only.")
    val isVal: Boolean,
    @DocField(description = "Whether the property has the const modifier.")
    val const: Boolean,
    @DocField(description = "Whether the property has the lateinit modifier.")
    val lateinit: Boolean,
    @DocField(description = "Whether the property is delegated.")
    val delegated: Boolean,
    @DocField(description = "Whether the property overrides another declaration.")
    val override: Boolean,
    @DocField(description = "Whether the compiler exposes the property as static.")
    val static: Boolean,
    @DocField(description = "Whether the property has the external modifier.")
    val external: Boolean,
    @DocField(description = "Whether the property has the expect modifier.")
    val expect: Boolean,
    @DocField(description = "Whether the property has the actual modifier.")
    val actual: Boolean,
) : ReplacementDeclarationSignature {
    val contextReceiverTypes: List<String>
        get() = Collections.unmodifiableList(storedContextReceiverTypes)
    val typeParameters: List<ReplacementTypeParameterSignature>
        get() = Collections.unmodifiableList(storedTypeParameters)

    init {
        require(name.isNotBlank()) { "Replacement property name must not be blank" }
        require(receiverType == null || receiverType.isNotBlank()) {
            "Replacement property receiver type must be null or non-blank"
        }
        require(storedContextReceiverTypes.all(String::isNotBlank)) {
            "Replacement property context receiver types must not be blank"
        }
        require(returnType.isNotBlank()) { "Replacement property return type must not be blank" }
    }

    override fun equals(other: Any?): Boolean = other is ReplacementPropertySignature &&
        name == other.name &&
        receiverType == other.receiverType &&
        storedContextReceiverTypes == other.storedContextReceiverTypes &&
        storedTypeParameters == other.storedTypeParameters &&
        returnType == other.returnType &&
        visibility == other.visibility &&
        modality == other.modality &&
        getterVisibility == other.getterVisibility &&
        setterVisibility == other.setterVisibility &&
        hasGetter == other.hasGetter &&
        hasSetter == other.hasSetter &&
        hasBackingField == other.hasBackingField &&
        isVal == other.isVal &&
        const == other.const &&
        lateinit == other.lateinit &&
        delegated == other.delegated &&
        override == other.override &&
        static == other.static &&
        external == other.external &&
        expect == other.expect &&
        actual == other.actual

    override fun hashCode(): Int = listOf(
        name,
        receiverType,
        storedContextReceiverTypes,
        storedTypeParameters,
        returnType,
        visibility,
        modality,
        getterVisibility,
        setterVisibility,
        hasGetter,
        hasSetter,
        hasBackingField,
        isVal,
        const,
        lateinit,
        delegated,
        override,
        static,
        external,
        expect,
        actual,
    ).hashCode()

    override fun toString(): String =
        "ReplacementPropertySignature(name=$name, receiverType=$receiverType, " +
            "contextReceiverTypes=$storedContextReceiverTypes, typeParameters=$storedTypeParameters, " +
            "returnType=$returnType, visibility=$visibility, modality=$modality, " +
            "getterVisibility=$getterVisibility, setterVisibility=$setterVisibility, hasGetter=$hasGetter, " +
            "hasSetter=$hasSetter, hasBackingField=$hasBackingField, isVal=$isVal, const=$const, " +
            "lateinit=$lateinit, delegated=$delegated, override=$override, static=$static, external=$external, " +
            "expect=$expect, actual=$actual)"

    companion object {
        fun of(
            name: String,
            receiverType: String?,
            contextReceiverTypes: List<String>,
            typeParameters: List<ReplacementTypeParameterSignature>,
            returnType: String,
            visibility: ReplacementVisibility,
            modality: ReplacementModality,
            getterVisibility: ReplacementVisibility,
            setterVisibility: ReplacementVisibility?,
            hasGetter: Boolean,
            hasSetter: Boolean,
            hasBackingField: Boolean,
            isVal: Boolean,
            const: Boolean,
            lateinit: Boolean,
            delegated: Boolean,
            override: Boolean,
            static: Boolean,
            external: Boolean,
            expect: Boolean,
            actual: Boolean,
        ): ReplacementPropertySignature = ReplacementPropertySignature(
            name = name,
            receiverType = receiverType,
            storedContextReceiverTypes = contextReceiverTypes.toList(),
            storedTypeParameters = typeParameters.toList(),
            returnType = returnType,
            visibility = visibility,
            modality = modality,
            getterVisibility = getterVisibility,
            setterVisibility = setterVisibility,
            hasGetter = hasGetter,
            hasSetter = hasSetter,
            hasBackingField = hasBackingField,
            isVal = isVal,
            const = const,
            lateinit = lateinit,
            delegated = delegated,
            override = override,
            static = static,
            external = external,
            expect = expect,
            actual = actual,
        )
    }
}
