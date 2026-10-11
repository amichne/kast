import io.github.amichne.kast.cli.KotlinxRequestJsonSchemaKt;
import io.github.amichne.kast.cli.InstalledServerProjectionDocumentsKt;
import io.github.amichne.kast.protocol.contract.CanonicalOperation;
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument;
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments;
import io.github.amichne.kast.query.contract.QueryLimitation;
import java.util.Arrays;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import kotlinx.serialization.json.Json;
import kotlin.jvm.internal.StringCompanionObject;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline schema export using the actual canonical serializer and schema owner. */
public class PiQuestionSchema {
    public static void main(String[] args) throws Exception {
        var schema = KotlinxRequestJsonSchemaKt.generatedRequestSchema(QueryQuestionDocument.Companion.serializer());
        Files.writeString(Path.of(args[0]), schema.toString() + "\n");
        var qualification = KotlinxRequestJsonSchemaKt.generatedRequestSchema(CanonicalQueryCliDocuments.INSTANCE.getQualificationSerializer());
        Files.writeString(Path.of(args[1]), qualification.toString() + "\n");
        var result = InstalledServerProjectionDocumentsKt.installedSemanticResultSchema(CanonicalOperation.QUERY_RUN);
        Files.writeString(Path.of(args[2]), result.toString() + "\n");
        if (args.length > 3) {
            var strings = BuiltinSerializersKt.serializer(StringCompanionObject.INSTANCE);
            var values = Arrays.stream(QueryLimitation.values()).map(Enum::name).toList();
            Files.writeString(Path.of(args[3]), Json.Default.encodeToString(BuiltinSerializersKt.ListSerializer(strings), values) + "\n");
        }
    }
}
