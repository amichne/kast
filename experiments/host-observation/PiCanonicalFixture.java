import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments;
import java.nio.file.Files;
import java.nio.file.Path;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.json.Json;

/** Offline fixture admission/serialization by the actual Kotlin document owner.
 * Run with the admitted release's lib/* classpath. No services or IDEs are
 * composed. Input seeds must already be public/synthetic and contain no logs.
 */
public class PiCanonicalFixture {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        KSerializer<Object> serializer = (KSerializer<Object>) switch (args[0]) {
            case "complete" -> CanonicalQueryCliDocuments.INSTANCE.getCompleteSerializer();
            case "qualified" -> CanonicalQueryCliDocuments.INSTANCE.getQualifiedSerializer();
            case "rejected_document" -> CanonicalQueryCliDocuments.INSTANCE.getRejectedSerializer();
            default -> throw new IllegalArgumentException("Known query document variant required");
        };
        Object admitted = Json.Default.decodeFromString(serializer, Files.readString(Path.of(args[1])));
        Files.writeString(Path.of(args[2]), Json.Default.encodeToString(serializer, admitted) + "\n");
    }
}
