package io.github.markpollack.journal.tck.ocsf;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import static io.github.markpollack.journal.tck.ocsf.TerminalReading.*;
/** Checks available referenced bytes, separately from detached interpretation. */
final class TerminalSourceReading {
    private TerminalSourceReading() { }
    static void check(java.nio.file.Path directory, TerminalBindingReader.Binding binding, List<Diagnostic> diagnostics) {
        if (binding == null) return;
        Json a = exact(binding.association());
        for (Json source : array(a, "sources")) {
            java.nio.file.Path path = directory.resolve("sources").resolve(string(source, "relativePath"));
            try {
                if (!java.nio.file.Files.isRegularFile(path)) {
                    diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, "TERMINAL_SOURCE_UNAVAILABLE", "external terminal evidence unavailable: " + string(source, "sourceId")));
                    continue;
                }
                verify(path, source);
            }
            catch (java.io.IOException e) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.DEFECT, "TERMINAL_SOURCE_INVALID", string(source, "sourceId") + ": " + e.getMessage()));
            }
        }
    }
    private static void verify(java.nio.file.Path path, Json source) throws java.io.IOException {
                if (!path.toRealPath().equals(path.toAbsolutePath().normalize())) throw new java.io.IOException("symlink evidence path");
                if (java.nio.file.Files.size(path) != number(source, "size")
                        || !NativeArtifact.sha256(java.nio.file.Files.readAllBytes(path)).equals(string(source, "sha256"))) throw new java.io.IOException("source hash/size mismatch");
    }

}
