import io.github.amichne.kast.cli.KotlinxRequestJsonSchemaKt;
import io.github.amichne.kast.cli.InstalledServerProjectionDocumentsKt;
import io.github.amichne.kast.protocol.contract.CanonicalOperation;
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument;
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments;
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
    }
}
