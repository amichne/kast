@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*

import kotlinx.serialization.Serializable

@Serializable
data class Symbol(
    @DocField(description = "Fully qualified name of the symbol, e.g. `com.example.MyClass.myFunction`.")
    val fqName: String,
    @DocField(description = "The kind of symbol (CLASS, FUNCTION, PROPERTY, etc.).")
    val kind: SymbolKind,
    @DocField(description = "Source location where the symbol is declared.")
    val location: Location,
    @DocField(description = "Type of the symbol for properties and parameters, e.g. `String`.")
    val type: String? = null,
    @DocField(description = "Return type for functions and methods.")
    val returnType: String? = null,
    @DocField(description = "Parameter list for functions, methods, and constructors.")
    val parameters: List<ParameterInfo>? = null,
    @DocField(description = "KDoc documentation attached to the symbol, if requested.")
    val documentation: String? = null,
    @DocField(description = "Fully qualified name of the enclosing declaration.")
    val containingDeclaration: String? = null,
    @DocField(description = "List of fully qualified supertype names for classes and interfaces.")
    val supertypes: List<String>? = null,
    @DocField(description = "Kotlin/Java visibility modifier of the symbol.")
    val visibility: SymbolVisibility? = null,
    @DocField(description = "Full text range of the declaration, if requested.")
    val declarationScope: DeclarationScope? = null,
)
