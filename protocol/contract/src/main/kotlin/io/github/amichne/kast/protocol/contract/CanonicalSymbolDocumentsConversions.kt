@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

fun RelationOccurrenceWireDocument.toContract(): WireDocumentConversion<RelationOccurrenceDocument> =
    combineConverted(
        candidateSelector.toProtocolText(),
        file.toProtocolText(),
        range.toContract(),
        ::RelationOccurrenceDocument,
    )

fun CompilerSymbolEvidenceDocument.toWireDocument(): CompilerSymbolEvidenceWireDocument =
    CompilerSymbolEvidenceWireDocument(identity.value, signature.toWireDocument())

fun CompilerSymbolEvidenceWireDocument.toContract(): WireDocumentConversion<CompilerSymbolEvidenceDocument> =
    combineConverted(
            identity.toProtocolText(),
            signature.toContract(),
        ) { identity, signature ->
            identity to signature
        }
        .flatMapConverted { (identity, signature) ->
            CompilerSymbolEvidenceDocument.restore(identity, signature).toWireDocumentConversion()
        }

fun CompilerSignatureDocument.toWireDocument(): CompilerSignatureWireDocument =
    when (this) {
        is CompilerSignatureDocument.Function ->
            CompilerSignatureWireDocument.Function(
                qualifiedIdentity.value,
                receiver.toWireDocument(),
                contextReceivers.values.map(ProtocolText::value),
                valueParameters.values.map(ProtocolText::value),
                typeParameterCount.value,
            )
        is CompilerSignatureDocument.Property ->
            CompilerSignatureWireDocument.Property(
                qualifiedIdentity.value,
                receiver.toWireDocument(),
                contextReceivers.values.map(ProtocolText::value),
                returnType.value,
            )
        is CompilerSignatureDocument.LocalFunction ->
            CompilerSignatureWireDocument.LocalFunction(
                address.toWireDocument(),
                receiver.toWireDocument(),
                contextReceivers.values.map(ProtocolText::value),
                valueParameters.values.map(ProtocolText::value),
                typeParameterCount.value,
                returnType.value,
            )
        is CompilerSignatureDocument.LocalProperty ->
            CompilerSignatureWireDocument.LocalProperty(
                address.toWireDocument(),
                returnType.value,
                when (mutability) {
                    LocalPropertyMutabilityDocument.VAL -> LocalPropertyMutabilityWireDocument.VAL
                    LocalPropertyMutabilityDocument.VAR -> LocalPropertyMutabilityWireDocument.VAR
                },
            )
        is CompilerSignatureDocument.AnonymousObject ->
            CompilerSignatureWireDocument.AnonymousObject(
                address.toWireDocument(),
                supertypes.values.map(ProtocolText::value),
            )
        is CompilerSignatureDocument.TypeAlias -> CompilerSignatureWireDocument.TypeAlias(qualifiedIdentity.value)
        is CompilerSignatureDocument.ClassLike -> CompilerSignatureWireDocument.ClassLike(qualifiedIdentity.value)
    }

fun CompilerSignatureWireDocument.toContract(): WireDocumentConversion<CompilerSignatureDocument> =
    when (this) {
        is CompilerSignatureWireDocument.Function ->
            combineConverted(
                qualifiedIdentity.toProtocolText(),
                receiver.toContract(),
                contextReceivers.toProtocolTextList(),
                valueParameters.toProtocolTextList(),
                CompilerTypeParameterCountDocument.parse(typeParameterCount).toWireDocumentConversion(),
            ) { qualified, receiver, contexts, parameters, typeParameters ->
                CompilerSignatureDocument.Function(
                    qualified,
                    receiver,
                    contexts,
                    parameters,
                    typeParameters,
                )
            }
        is CompilerSignatureWireDocument.Property ->
            combineConverted(
                qualifiedIdentity.toProtocolText(),
                receiver.toContract(),
                contextReceivers.toProtocolTextList(),
                returnType.toProtocolText(),
            ) { qualified, receiver, contextReceivers, returnType ->
                CompilerSignatureDocument.Property(
                    qualified,
                    receiver,
                    contextReceivers,
                    returnType,
                )
            }
        is CompilerSignatureWireDocument.LocalFunction -> localFunctionContract()
        is CompilerSignatureWireDocument.LocalProperty -> localPropertyContract()
        is CompilerSignatureWireDocument.AnonymousObject ->
            if (address.kind != LocalDeclarationKindWireDocument.ANONYMOUS_OBJECT) WireDocumentConversion.Rejected
            else
                combineConverted(address.toContract(), supertypes.toProtocolTextList()) { address, types ->
                    CompilerSignatureDocument.AnonymousObject(address, types)
                }
        is CompilerSignatureWireDocument.TypeAlias ->
            qualifiedIdentity.toProtocolText().mapConverted {
                CompilerSignatureDocument.TypeAlias(it)
            }
        is CompilerSignatureWireDocument.ClassLike ->
            qualifiedIdentity.toProtocolText().mapConverted {
                CompilerSignatureDocument.ClassLike(it)
            }
    }

private fun CompilerSignatureWireDocument.LocalFunction.localFunctionContract():
    WireDocumentConversion<CompilerSignatureDocument> =
    if (address.kind != LocalDeclarationKindWireDocument.FUNCTION) WireDocumentConversion.Rejected
    else
        returnType.toProtocolText().flatMapConverted { result ->
            combineConverted(
                address.toContract(),
                receiver.toContract(),
                contextReceivers.toProtocolTextList(),
                valueParameters.toProtocolTextList(),
                CompilerTypeParameterCountDocument.parse(typeParameterCount).toWireDocumentConversion(),
            ) { address, receiver, contexts, parameters, count ->
                CompilerSignatureDocument.LocalFunction(address, receiver, contexts, parameters, count, result)
            }
        }

private fun CompilerSignatureWireDocument.LocalProperty.localPropertyContract():
    WireDocumentConversion<CompilerSignatureDocument> =
    if (address.kind != LocalDeclarationKindWireDocument.PROPERTY) WireDocumentConversion.Rejected
    else
        combineConverted(address.toContract(), returnType.toProtocolText()) { address, result ->
            CompilerSignatureDocument.LocalProperty(
                address,
                result,
                when (mutability) {
                    LocalPropertyMutabilityWireDocument.VAL -> LocalPropertyMutabilityDocument.VAL
                    LocalPropertyMutabilityWireDocument.VAR -> LocalPropertyMutabilityDocument.VAR
                },
            )
        }

fun LocalDeclarationAddressDocument.toWireDocument(): LocalDeclarationAddressWireDocument =
    LocalDeclarationAddressWireDocument(
        when (val location = file) {
            is LocalDeclarationFileDocument.Workspace ->
                LocalDeclarationFileWireDocument.Workspace(location.value.value)
            is LocalDeclarationFileDocument.External -> LocalDeclarationFileWireDocument.External(location.value.value)
        },
        when (kind) {
            LocalDeclarationKindDocument.FUNCTION -> LocalDeclarationKindWireDocument.FUNCTION
            LocalDeclarationKindDocument.PROPERTY -> LocalDeclarationKindWireDocument.PROPERTY
            LocalDeclarationKindDocument.ANONYMOUS_OBJECT -> LocalDeclarationKindWireDocument.ANONYMOUS_OBJECT
        },
        range.toWireDocument(),
        ownerIdentity.value,
        ownerRange.toWireDocument(),
        lexicalOwners.values.map { it.toWireDocument() },
    )

fun LocalDeclarationAddressWireDocument.toContract(): WireDocumentConversion<LocalDeclarationAddressDocument> =
    combineConverted(
            file.toContract(),
            range.toContract(),
            ownerIdentity.toProtocolText(),
            ownerRange.toContract(),
            lexicalOwners.convertEach { it.toContract() }.flatMapConverted { it.toBoundedList() },
        ) { file, range, owner, ownerRange, lexical ->
            LocalDeclarationAddressDocument.create(
                    file,
                    when (kind) {
                        LocalDeclarationKindWireDocument.FUNCTION -> LocalDeclarationKindDocument.FUNCTION
                        LocalDeclarationKindWireDocument.PROPERTY -> LocalDeclarationKindDocument.PROPERTY
                        LocalDeclarationKindWireDocument.ANONYMOUS_OBJECT ->
                            LocalDeclarationKindDocument.ANONYMOUS_OBJECT
                    },
                    range,
                    owner,
                    ownerRange,
                    lexical,
                )
                .toWireDocumentConversion()
        }
        .flattenConverted()

fun LocalDeclarationFileWireDocument.toContract(): WireDocumentConversion<LocalDeclarationFileDocument> =
    when (this) {
        is LocalDeclarationFileWireDocument.Workspace ->
            path.toProtocolText().mapConverted { LocalDeclarationFileDocument.Workspace(it) }
        is LocalDeclarationFileWireDocument.External ->
            url.toProtocolText().mapConverted { LocalDeclarationFileDocument.External(it) }
    }

fun CompilerReceiverDocument.toWireDocument(): CompilerReceiverWireDocument =
    when (this) {
        CompilerReceiverDocument.Absent -> CompilerReceiverWireDocument.Absent
        is CompilerReceiverDocument.Present -> CompilerReceiverWireDocument.Present(compilerType.value)
    }

fun CompilerReceiverWireDocument.toContract(): WireDocumentConversion<CompilerReceiverDocument> =
    when (this) {
        CompilerReceiverWireDocument.Absent -> WireDocumentConversion.Converted(CompilerReceiverDocument.Absent)
        is CompilerReceiverWireDocument.Present ->
            compilerType.toProtocolText().mapConverted {
                CompilerReceiverDocument.Present(it)
            }
    }

private fun List<String>.toProtocolTextList(): WireDocumentConversion<BoundedProtocolList<ProtocolText>> =
    convertEach(String::toProtocolText).flatMapConverted { values -> values.toBoundedList() }

fun SourceRangeDocument.toWireDocument() = SourceRangeWireDocument(startInclusive.value, endExclusive.value)

/**
 * Proof transition: `SourceRangeWireDocument -> WireDocumentConversion<SourceRangeDocument>`. Establishes non-negative,
 * ordered offsets; [WireDocumentConversion.Rejected] is the closed expected failure and raw offsets exist only at this
 * wire boundary.
 */
fun SourceRangeWireDocument.toContract(): WireDocumentConversion<SourceRangeDocument> =
    combineConverted(startInclusive.toProtocolOffset(), endExclusive.toProtocolOffset()) { start, end ->
            start to end
        }
        .flatMapConverted { (start, end) ->
            SourceRangeDocument.create(start, end).toWireDocumentConversion()
        }

fun SymbolKindDocument.toWireDocument() =
    when (this) {
        SymbolKindDocument.CLASSLIKE -> SymbolKindWireDocument.CLASSLIKE
        SymbolKindDocument.CONSTRUCTOR -> SymbolKindWireDocument.CONSTRUCTOR
        SymbolKindDocument.FUNCTION -> SymbolKindWireDocument.FUNCTION
        SymbolKindDocument.PROPERTY -> SymbolKindWireDocument.PROPERTY
        SymbolKindDocument.TYPE_ALIAS -> SymbolKindWireDocument.TYPE_ALIAS
    }

fun SymbolKindWireDocument.toContract() =
    when (this) {
        SymbolKindWireDocument.CLASSLIKE -> SymbolKindDocument.CLASSLIKE
        SymbolKindWireDocument.CONSTRUCTOR -> SymbolKindDocument.CONSTRUCTOR
        SymbolKindWireDocument.FUNCTION -> SymbolKindDocument.FUNCTION
        SymbolKindWireDocument.PROPERTY -> SymbolKindDocument.PROPERTY
        SymbolKindWireDocument.TYPE_ALIAS -> SymbolKindDocument.TYPE_ALIAS
    }

/** `String -> WireDocumentConversion<ProtocolText>`; closes invalid text at the wire boundary. */
fun String.toProtocolText(): WireDocumentConversion<ProtocolText> = ProtocolText.parse(this).toWireDocumentConversion()

/** `Int -> WireDocumentConversion<ProtocolOffset>`; closes invalid offsets at the wire boundary. */
private fun Int.toProtocolOffset(): WireDocumentConversion<ProtocolOffset> =
    ProtocolOffset.parse(this).toWireDocumentConversion()

/** `List<Value> -> WireDocumentConversion<BoundedProtocolList<Value>>`; proves the wire bound. */
private fun <Value> List<Value>.toBoundedList(): WireDocumentConversion<BoundedProtocolList<Value>> =
    BoundedProtocolList.create(this).toWireDocumentConversion()
