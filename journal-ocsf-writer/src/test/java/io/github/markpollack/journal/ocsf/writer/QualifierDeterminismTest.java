package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qualifying one sealed Client manifest gives the same qualified manifest bytes in every JVM: each real run is qualified
 * in separate JVMs and must give one {@code acquisition.json}. Map.of iteration order changes from one JVM to the next,
 * so an object built from Map.of is written in a different member order from run to run.
 */
class QualifierDeterminismTest {
    private static final int JVMS = 8;

    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"txbench-2.1.319", "codex-one-read"})
    void qualifyingOneManifestInSeparateJvmsWritesOneQualifiedManifest(String run) throws Exception {
        Path sealed = RealRecording.nativeDir(RealRecording.PUBLIC.resolve(run)).resolve("client-acquisition.json");
        String profile = Jv.parse(Files.readString(sealed)).member("profile").text();
        Set<String> manifests = new HashSet<>();
        for (int i = 0; i < JVMS; i++) {
            Path out = temp.resolve("qualified-" + i);
            List<String> cmd = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                    System.getProperty("java.class.path"), Qualify.class.getName(), profile, sealed.toString(), out.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(temp.resolve("out-" + i).toFile()).start();
            assertThat(p.waitFor()).as(Files.readString(temp.resolve("out-" + i))).isZero();
            manifests.add(Files.readString(out.resolve("acquisition.json")));
        }
        assertThat(manifests).as("distinct qualified manifests across %d JVMs", JVMS).hasSize(1);
    }

    /** Runs the profile's qualifier once: {@code <profile> <sealed manifest> <new directory>}. */
    static final class Qualify {
        private Qualify() { }

        public static void main(String[] args) throws Exception {
            AcquisitionQualifier.forProfile(args[0]).orElseThrow().qualify(Path.of(args[1]), Path.of(args[2]));
        }
    }
}
