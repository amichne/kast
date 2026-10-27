import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments;
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;
import kotlinx.serialization.json.JsonKt;
import kotlin.Unit;
import kotlin.jvm.JvmClassMappingKt;
import kotlinx.serialization.modules.SerializersModuleBuilder;

/** Offline fixture admission/serialization by the actual Kotlin document owner.
 * Run with the admitted release's lib/* classpath. No services or IDEs are
 * composed. Input seeds must already be public/synthetic and contain no logs.
 */
public class PiCanonicalFixture {
    public static void main(String[] args) throws Exception {
        Json decoder = decoder(args);
        if (args[0].equals("native-projection")) {
            var source = (JsonObject) Json.Default.parseToJsonElement(Files.readString(Path.of(args[1])));
            var cases = (JsonObject) source.get("cases");
            var projected = new LinkedHashMap<String, JsonElement>();
            for (var entry : cases.entrySet()) {
                var receipt = (JsonObject) entry.getValue();
                var reply = (JsonObject) receipt.get("reply");
                var kind = ((JsonPrimitive) reply.get("type")).getContent();
                projected.put(entry.getKey(), project(kind, reply.get("document").toString(), decoder));
            }
            Files.writeString(Path.of(args[2]), new JsonObject(projected).toString() + "\n");
        } else {
            var document = project(args[0], Files.readString(Path.of(args[1])), decoder);
            Files.writeString(Path.of(args[2]), document.toString() + "\n");
        }
    }

    @SuppressWarnings("unchecked")
    private static JsonElement project(String kind, String source, Json decoder) {
        KSerializer<Object> serializer = (KSerializer<Object>) switch (kind) {
            case "complete" -> CanonicalQueryCliDocuments.INSTANCE.getCompleteSerializer();
            case "qualified" -> CanonicalQueryCliDocuments.INSTANCE.getQualifiedSerializer();
            case "rejected_document" -> CanonicalQueryCliDocuments.INSTANCE.getRejectedSerializer();
            default -> throw new IllegalArgumentException("Known query document variant required");
        };
        Object admitted = decoder.decodeFromString(serializer, source);
        return Json.Default.encodeToJsonElement(serializer, admitted);
    }

    private static Json decoder(String[] args) {
        if (args.length <= 3 || !args[3].equals("--legacy-nullable-input")) return Json.Default;
        return JsonKt.Json(Json.Default, builder -> {
            builder.setExplicitNulls(false);
            if (args.length > 4 && args[4].equals("--legacy-bounded-input")) {
                var module = new SerializersModuleBuilder();
                module.polymorphicDefaultDeserializer(
                        JvmClassMappingKt.getKotlinClass(ExecutionBudgetReport.class),
                        name -> name == null ? ExecutionBudgetReport.Bounded.Companion.serializer() : null);
                builder.setSerializersModule(module.build());
            }
            return Unit.INSTANCE;
        });
    }
}
