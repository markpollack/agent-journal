package io.github.markpollack.journal.tck.ocsf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line entry point of the compatible Java reader, for the isolated-bootstrap proof (an empty directory, no
 * network, no caches, no Level 0, no optional files):
 *
 * <pre>
 *   inspect        &lt;events.jsonl|dir&gt;                 discover the manifest, verify pins/hashes, enumerate the saved model
 *   views          &lt;events.jsonl|dir&gt; [out.json]      the analysis views from the core alone
 *   measure        &lt;events.jsonl|dir&gt; [out.json]      byte measurements per component
 *   reconstruct    &lt;events.jsonl|dir&gt; &lt;artifact&gt; &lt;out&gt;  scoped reconstruction of one source artifact
 *   self-contained &lt;events.jsonl|dir&gt; &lt;out.jsonl&gt;     export events with the shared facts joined back on
 *   verify         &lt;events.jsonl|dir&gt; &lt;native-dir&gt; [report.json]  the independent oracle against native artifacts
 * </pre>
 *
 * Exit status: 0 on success, 2 when the reader refused the package, 3 when the oracle found defects.
 */
public final class OcsfReaderTool {

    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private OcsfReaderTool() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: inspect|views|measure|reconstruct|self-contained|verify <events.jsonl|dir> ...");
            System.exit(64);
        }
        OcsfPackage pkg = PackageReader.open(Path.of(args[1]));
        switch (args[0]) {
            case "inspect" -> {
                for (OcsfPackage core : pkg.all()) {
                    System.out.println("== core " + core.label() + " " + (core.manifest == null ? "(no manifest)" : core.manifest.runUid()) + " at " + core.dir);
                    if (core.manifest != null) {
                        System.out.println("package " + core.manifest.packageKind() + " " + core.manifest.packageVersion()
                                + "; reader contract " + core.manifest.readerContract() + " " + core.manifest.readerContractVersion()
                                + "; extension " + core.manifest.extensionName() + " uid " + core.manifest.extensionUid() + " " + core.manifest.extensionVersion()
                                + "; OCSF " + core.manifest.text("ocsf", "version") + "; contracts " + core.manifest.contracts().keySet());
                    }
                    System.out.println("events " + core.events.size() + " (raw lines " + core.rawLines.size() + "), children " + core.children.size());
                    for (Diagnostic d : core.diagnostics) {
                        System.out.println("  " + d);
                    }
                    if (core.catalog != null) {
                        System.out.print(core.catalog.enumerate());
                    }
                }
                System.exit(pkg.refused() ? 2 : 0);
            }
            case "views" -> write(AnalysisViews.all(pkg), args.length > 2 ? Path.of(args[2]) : null);
            case "measure" -> {
                for (OcsfPackage core : pkg.all()) {
                    write(PackageMeasure.measure(core), args.length > 2 ? Path.of(args[2] + (core == pkg ? "" : "." + core.label().replace(':', '_'))) : null);
                }
            }
            case "reconstruct" -> {
                String artifact = args[2];
                List<Event> events = pkg.events.stream().filter(e -> e.className != null && artifact.equals(e.artifact())).toList();
                Reconstruction.Result r = Reconstruction.reconstruct(events, Integer.MAX_VALUE);
                Files.write(Path.of(args[3]), r.bytes());
                System.out.println("reconstructed " + r.lines().size() + " record(s) of " + artifact + " to " + args[3]
                        + " (parsed records value-exact, opaque records byte-exact)");
            }
            case "self-contained" -> {
                StringBuilder sb = new StringBuilder();
                for (Event e : pkg.events) {
                    sb.append(NeutralJson.write(PackageReader.selfContained(pkg, e))).append('\n');
                }
                Files.writeString(Path.of(args[2]), sb.toString(), StandardCharsets.UTF_8);
                System.out.println("wrote " + pkg.events.size() + " self-contained event(s) to " + args[2]);
            }
            case "verify" -> {
                OracleReport report = OcsfOracle.verify(pkg, Path.of(args[2]));
                String json = PRETTY.writeValueAsString(report.toMap());
                if (args.length > 3) {
                    Files.writeString(Path.of(args[3]), json, StandardCharsets.UTF_8);
                }
                System.out.println(PRETTY.writeValueAsString(report.summary()));
                for (io.github.markpollack.journal.tck.verify.Check c : report.checks()) {
                    if (c.outcome() != io.github.markpollack.journal.tck.verify.Check.Outcome.VERIFIED
                            && c.outcome() != io.github.markpollack.journal.tck.verify.Check.Outcome.NOT_APPLICABLE) {
                        System.out.println("  " + c);
                    }
                }
                System.exit(report.defects().isEmpty() ? 0 : 3);
            }
            default -> {
                System.err.println("unknown command " + args[0]);
                System.exit(64);
            }
        }
    }

    private static void write(Json json, Path out) throws IOException {
        String text = PRETTY.writeValueAsString(PRETTY.readTree(NeutralJson.write(json)));
        if (out == null) {
            System.out.println(text);
        }
        else {
            Files.writeString(out, text, StandardCharsets.UTF_8);
            System.out.println("wrote " + out);
        }
    }
}
