package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactStringContentInputDocument
import io.github.amichne.kast.protocol.contract.ImpactStringNullAlternativeDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.relation.contract.ValueStringContent
import io.github.amichne.kast.relation.contract.ValueStringContentInput
import io.github.amichne.kast.relation.contract.ValueStringNullAlternative

internal fun ValueStringContent.impactStringContent(): ImpactProjected<ImpactTransferEvidenceDocument.StringContent> =
    templateRange
        .impactDocument()
        .impactZip(entryRange.impactDocument())
        .impactZip(inputRange.impactDocument())
        .impactMap { (ranges, inputRange) ->
            ImpactTransferEvidenceDocument.StringContent(
                templateRange = ranges.first,
                entryRange = ranges.second,
                inputRange = inputRange,
                input =
                    when (input) {
                        ValueStringContentInput.STRING -> ImpactStringContentInputDocument.STRING
                        ValueStringContentInput.NULLABLE_STRING -> ImpactStringContentInputDocument.NULLABLE_STRING
                    },
                nullAlternative =
                    when (nullAlternative) {
                        ValueStringNullAlternative.NOT_NULLABLE -> ImpactStringNullAlternativeDocument.NOT_NULLABLE
                        ValueStringNullAlternative.CONSTANT_NULL_TEXT_WITHOUT_SOURCE_CONTENT ->
                            ImpactStringNullAlternativeDocument.CONSTANT_NULL_TEXT_WITHOUT_SOURCE_CONTENT
                    },
            )
        }

internal fun io.github.amichne.kast.relation.contract.ValueStringConstruction.impactDocument():
    ImpactProjected<io.github.amichne.kast.protocol.contract.ImpactStringConstructionDocument> =
    source
        .impactDocument()
        .impactZip(templateRange.impactDocument())
        .impactZip(entryRange.impactDocument())
        .impactZip(inputRange.impactDocument())
        .impactMap { (record, input) ->
            io.github.amichne.kast.protocol.contract.ImpactStringConstructionDocument(
                record.first.first,
                record.first.second,
                record.second,
                input,
                io.github.amichne.kast.protocol.contract.ImpactStringConstructionKindDocument
                    .CONSTANT_NULL_TEXT_WITHOUT_SOURCE_CONTENT,
            )
        }

internal fun io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence.StringConversion
    .impactStringConversion():
    ImpactProjected<io.github.amichne.kast.protocol.contract.ImpactFlowObligationEvidenceDocument.StringConversion> =
    selection.compilerIdentity.value
        .impactText()
        .impactZip(selection.signature.canonicalEncoding().value.impactText())
        .impactZip(entryRange.impactDocument())
        .impactZip(inputRange.impactDocument())
        .impactMap { (record, input) ->
            io.github.amichne.kast.protocol.contract.ImpactFlowObligationEvidenceDocument.StringConversion(
                record.first.first,
                record.first.second,
                selection.origin.impactOrigin(),
                record.second,
                input,
            )
        }

private fun io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.impactOrigin():
    io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument =
    when (this) {
        io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.SOURCE ->
            io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument.SOURCE
        io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.SOURCE_MEMBER_GENERATED ->
            io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument.SOURCE_MEMBER_GENERATED
        io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.LIBRARY ->
            io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument.LIBRARY
        io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.JAVA_SOURCE ->
            io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument.JAVA_SOURCE
        io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.JAVA_LIBRARY ->
            io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument.JAVA_LIBRARY
        io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.SAM_CONSTRUCTOR ->
            io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument.SAM_CONSTRUCTOR
        io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.TYPEALIASED_CONSTRUCTOR ->
            io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument.TYPEALIASED_CONSTRUCTOR
        io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.INTERSECTION_OVERRIDE ->
            io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument.INTERSECTION_OVERRIDE
        io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.SUBSTITUTION_OVERRIDE ->
            io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument.SUBSTITUTION_OVERRIDE
        io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.DELEGATED ->
            io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument.DELEGATED
        io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.JAVA_SYNTHETIC_PROPERTY ->
            io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument.JAVA_SYNTHETIC_PROPERTY
        io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.PROPERTY_BACKING_FIELD ->
            io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument.PROPERTY_BACKING_FIELD
        io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.PLUGIN ->
            io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument.PLUGIN
        io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.JS_DYNAMIC ->
            io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument.JS_DYNAMIC
        io.github.amichne.kast.relation.contract.ValueStringConversionOrigin.NATIVE_FORWARD_DECLARATION ->
            io.github.amichne.kast.protocol.contract.ImpactStringConversionOriginDocument.NATIVE_FORWARD_DECLARATION
    }
