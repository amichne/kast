import io.github.amichne.kast.cli.KotlinxRequestJsonSchemaKt;
import io.github.amichne.kast.cli.InstalledServerProjectionDocumentsKt;
import io.github.amichne.kast.cli.CanonicalSchemaReferences;
import io.github.amichne.kast.protocol.contract.CanonicalOperation;
import io.github.amichne.kast.protocol.contract.ImpactCallFrameDocument;
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument;
import io.github.amichne.kast.protocol.contract.ImpactValueConditionOriginDocument;
import io.github.amichne.kast.protocol.contract.ImpactCaptureInvocationDocument;
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument;
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument;
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument;
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument;
import io.github.amichne.kast.protocol.contract.ImpactReviewedCallValueBindingDocument;
import io.github.amichne.kast.protocol.contract.ImpactReviewedTupleOriginDocument;
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapEntryCorrelationDocument;
import io.github.amichne.kast.protocol.contract.ImpactReviewedDistinctStringKeysDocument;
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapEntryOriginDocument;
import io.github.amichne.kast.protocol.contract.ImpactReviewedMapEntryFamilyDocument;
import io.github.amichne.kast.protocol.contract.ImpactReviewedInputEntryOriginDocument;
import io.github.amichne.kast.protocol.contract.ImpactReviewedPreservedKeyOriginDocument;
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryReadOrderDocument;
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryComponentInvocationDocument;
import io.github.amichne.kast.protocol.contract.ImpactReviewedMappedEntryOriginDocument;
import io.github.amichne.kast.protocol.contract.ImpactReviewedCallbackParentDocument;
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument;
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments;
import io.github.amichne.kast.query.contract.QueryLimitation;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonKt;
import kotlin.Unit;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonObject;
import kotlin.jvm.internal.StringCompanionObject;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline schema export using the actual canonical serializer and schema owner. */
public class PiQuestionSchema {
    public static void main(String[] args) throws Exception {
        Json readable = JsonKt.Json(Json.Default, options -> {
            options.setPrettyPrint(true);
            options.setPrettyPrintIndent("  ");
            return Unit.INSTANCE;
        });
        var sharedQuestionProofs = Map.<String, KSerializer<?>>of(
                "impactCallFrame", ImpactCallFrameDocument.Companion.serializer(),
                "impactDeclarationReference", ImpactDeclarationReferenceDocument.Companion.serializer(),
                "impactValueConditionOrigin", ImpactValueConditionOriginDocument.Companion.serializer());
        var schema = recursiveSchema(QueryQuestionDocument.Companion.serializer(), sharedQuestionProofs);
        Files.writeString(Path.of(args[0]), readable.encodeToString(JsonObject.Companion.serializer(), schema) + "\n");
        var qualification = recursiveSchema(CanonicalQueryCliDocuments.INSTANCE.getQualificationSerializer(), Map.of());
        Files.writeString(Path.of(args[1]), qualification.toString() + "\n");
        var result = InstalledServerProjectionDocumentsKt.installedSemanticResultSchema(CanonicalOperation.QUERY_RUN);
        Files.writeString(Path.of(args[2]), readable.encodeToString(JsonObject.Companion.serializer(), result) + "\n");
        if (args.length > 3) {
            var strings = BuiltinSerializersKt.serializer(StringCompanionObject.INSTANCE);
            var values = Arrays.stream(QueryLimitation.values()).map(Enum::name).toList();
            Files.writeString(Path.of(args[3]), Json.Default.encodeToString(BuiltinSerializersKt.ListSerializer(strings), values) + "\n");
        }
    }

    private static JsonObject recursiveSchema(KSerializer<?> serializer, Map<String, KSerializer<?>> extra) {
        Map<String, KSerializer<?>> shared = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("impactInvocationReference", ImpactInvocationReferenceDocument.Companion.serializer()),
                Map.entry("impactCaptureInvocation", ImpactCaptureInvocationDocument.Companion.serializer()),
                Map.entry("impactValueSiteReference", ImpactValueSiteReferenceDocument.Companion.serializer()),
                Map.entry("impactExecutionOwner", ImpactExecutionOwnerDocument.Companion.serializer()),
                Map.entry("impactReviewedValueBinding", ImpactReviewedValueBindingDocument.Companion.serializer()),
                Map.entry("impactReviewedCallValueBinding", ImpactReviewedCallValueBindingDocument.Companion.serializer()),
                Map.entry("impactReviewedTupleOrigin", ImpactReviewedTupleOriginDocument.Companion.serializer()),
                Map.entry("impactReviewedMapEntryCorrelation", ImpactReviewedMapEntryCorrelationDocument.Companion.serializer()),
                Map.entry("impactReviewedDistinctStringKeys", ImpactReviewedDistinctStringKeysDocument.Companion.serializer()),
                Map.entry("impactReviewedMapEntryOrigin", ImpactReviewedMapEntryOriginDocument.Companion.serializer()),
                Map.entry("impactReviewedMapEntryFamily", ImpactReviewedMapEntryFamilyDocument.Companion.serializer()),
                Map.entry("impactReviewedInputEntryOrigin", ImpactReviewedInputEntryOriginDocument.Companion.serializer()),
                Map.entry("impactReviewedPreservedKeyOrigin", ImpactReviewedPreservedKeyOriginDocument.Companion.serializer()),
                Map.entry("impactReviewedEntryReadOrder", ImpactReviewedEntryReadOrderDocument.Companion.serializer()),
                Map.entry("impactReviewedEntryComponentInvocation", ImpactReviewedEntryComponentInvocationDocument.Companion.serializer()),
                Map.entry("impactReviewedMappedEntryOrigin", ImpactReviewedMappedEntryOriginDocument.Companion.serializer()),
                Map.entry("impactReviewedCallbackParent", ImpactReviewedCallbackParentDocument.Companion.serializer())));
        shared.putAll(extra);
        var references = CanonicalSchemaReferences.Companion.from(shared);
        var root = new LinkedHashMap<String, JsonElement>(KotlinxRequestJsonSchemaKt.generatedOutputSchema(serializer, references));
        var definitions = new LinkedHashMap<String, JsonElement>();
        new java.util.TreeMap<>(shared).forEach((name, definition) -> definitions.put(name,
                KotlinxRequestJsonSchemaKt.generatedOutputSchema(definition, references)));
        root.put("$defs", new JsonObject(definitions));
        return new JsonObject(root);
    }
}
