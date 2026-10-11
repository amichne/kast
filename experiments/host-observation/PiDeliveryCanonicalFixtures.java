import io.github.amichne.kast.cli.rpc.QueryDeliveryFixtures;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline export using #995's compiled production-serialized, schema-admitted
 * fixture owner. Its source/compiled identity must be pinned by the caller.
 */
public class PiDeliveryCanonicalFixtures {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[0]), QueryDeliveryFixtures.INSTANCE.serialized() + "\n");
    }
}
