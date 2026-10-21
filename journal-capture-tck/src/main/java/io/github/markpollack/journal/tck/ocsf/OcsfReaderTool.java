package io.github.markpollack.journal.tck.ocsf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
 *   verify         &lt;events.jsonl|dir&gt; &lt;native-dir&gt; [report.json] [--expected &lt;expected-sources.json&gt;]
 *                                                     the independent oracle against the frozen expected sources
 * </pre>
 *
 * Exit status, every command: {@link #EXIT_OK} 0; {@link #EXIT_REFUSED} 2 when the reader refused the package
 * (detached events file, unsupported version, pin mismatch; raw lines are still kept, nothing is interpreted);
 * {@link #EXIT_USAGE} 64 on bad arguments. {@code verify} additionally: {@link #EXIT_DEFECTS} 3 when the oracle
 * found a defect; {@link #EXIT_INCOMPLETE} 4 when it found no defect but a mandatory property is UNRESOLVED or
 * UNSUPPORTED, a required check is missing from the report, the package is not finalized, acquisition is not
 * complete, no frozen expected-source manifest was supplied ({@code --expected} or {@code <native-dir>/expected-sources.json}),
 * or an acquired population was waived by a declared exclusion. Zero defects is therefore not acceptance; only 0 is.
 */
public final class OcsfReaderTool {

    public static final int EXIT_OK = 0;
    public static final int EXIT_REFUSED = 2;
    public static final int EXIT_DEFECTS = 3;
    public static final int EXIT_INCOMPLETE = 4;
    public static final int EXIT_USAGE = 64;

    /**
     * Properties whose UNSUPPORTED outcome is by design and does not block acceptance: byte-exact reconstruction of
     * parsed records is observed, not promised (the promise is structure/value plus number spelling).
     */
    public static final Set<String> OPTIONAL_PROPERTIES = Set.of("reconstruction.bytes");

    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private OcsfReaderTool() {
    }

    public static void main(String[] args) throws IOException {
        System.exit(run(args, System.out, System.err));
    }

    /** The whole tool without {@code System.exit}, for tests. */
    public static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length < 2) {
            err.println("usage: inspect|views|measure|reconstruct|self-contained|verify <events.jsonl|dir> ...");
            return EXIT_USAGE;
        }
        OcsfPackage pkg = PackageReader.open(Path.of(args[1]));
        switch (args[0]) {
            case "inspect" -> {
                for (OcsfPackage core : pkg.all()) {
                    out.println("== core " + core.label() + " " + (core.manifest == null ? "(no manifest)" : core.manifest.runUid()) + " at " + core.dir);
                    if (core.manifest != null) {
                        out.println("package " + core.manifest.packageKind() + " " + core.manifest.packageVersion() + " state " + core.manifest.packageState()
                                + "; reader contract " + core.manifest.readerContract() + " " + core.manifest.readerContractVersion()
                                + "; extension " + core.manifest.extensionName() + " uid " + core.manifest.extensionUid() + " " + core.manifest.extensionVersion()
                                + "; OCSF " + core.manifest.text("ocsf", "version") + "; contracts " + core.manifest.contracts().keySet());
                    }
                    out.println("events " + core.events.size() + " (raw lines " + core.rawLines.size() + "), children " + core.children.size());
                    for (Diagnostic d : core.diagnostics) {
                        out.println("  " + d);
                    }
                    if (core.catalog != null) {
                        out.print(core.catalog.enumerate());
                    }
                }
                return pkg.refused() ? EXIT_REFUSED : EXIT_OK;
            }
            case "views", "measure", "reconstruct", "self-contained", "verify" -> {
                if (pkg.refused()) {
                    err.println("refused: " + pkg.diagnostics(Diagnostic.Severity.REFUSED));
                    return EXIT_REFUSED;
                }
            }
            default -> {
                err.println("unknown command " + args[0]);
                return EXIT_USAGE;
            }
        }
        switch (args[0]) {
            case "views" -> write(AnalysisViews.all(pkg), args.length > 2 ? Path.of(args[2]) : null, out);
            case "measure" -> {
                for (OcsfPackage core : pkg.all()) {
                    write(PackageMeasure.measure(core), args.length > 2 ? Path.of(args[2] + (core == pkg ? "" : "." + core.label().replace(':', '_'))) : null, out);
                }
            }
            case "reconstruct" -> {
                if (args.length < 4) {
                    err.println("usage: reconstruct <events.jsonl|dir> <artifact> <out>");
                    return EXIT_USAGE;
                }
                String artifact = args[2];
                List<Event> events = pkg.events.stream().filter(e -> e.className != null && artifact.equals(e.artifact())).toList();
                Reconstruction.Result r = Reconstruction.reconstruct(events, Integer.MAX_VALUE);
                Files.write(Path.of(args[3]), r.bytes());
                out.println("reconstructed " + r.lines().size() + " record(s) of " + artifact + " to " + args[3]
                        + " (parsed records value-exact, opaque records byte-exact)");
            }
            case "self-contained" -> {
                if (args.length < 3) {
                    err.println("usage: self-contained <events.jsonl|dir> <out.jsonl>");
                    return EXIT_USAGE;
                }
                StringBuilder sb = new StringBuilder();
                for (Event e : pkg.events) {
                    sb.append(NeutralJson.write(PackageReader.selfContained(pkg, e))).append('\n');
                }
                Files.writeString(Path.of(args[2]), sb.toString(), StandardCharsets.UTF_8);
                out.println("wrote " + pkg.events.size() + " self-contained event(s) to " + args[2]);
            }
            case "verify" -> {
                List<String> positional = new ArrayList<>();
                Path expectedFile = null;
                for (int i = 2; i < args.length; i++) {
                    if ("--expected".equals(args[i])) {
                        if (i + 1 >= args.length) {
                            err.println("usage: verify <events.jsonl|dir> <native-dir> [report.json] [--expected <expected-sources.json>]");
                            return EXIT_USAGE;
                        }
                        expectedFile = Path.of(args[++i]);
                    }
                    else {
                        positional.add(args[i]);
                    }
                }
                if (positional.isEmpty()) {
                    err.println("usage: verify <events.jsonl|dir> <native-dir> [report.json] [--expected <expected-sources.json>]");
                    return EXIT_USAGE;
                }
                Path nativeDir = Path.of(positional.get(0));
                ExpectedSources expected = expectedFile != null ? ExpectedSources.read(expectedFile) : ExpectedSources.beside(nativeDir);
                OracleReport report = OcsfOracle.verify(pkg, nativeDir, expected);
                String json = PRETTY.writeValueAsString(report.toMap());
                if (positional.size() > 1) {
                    Files.writeString(Path.of(positional.get(1)), json, StandardCharsets.UTF_8);
                }
                out.println(PRETTY.writeValueAsString(report.summary()));
                for (Check c : report.checks()) {
                    if (c.outcome() != Check.Outcome.VERIFIED && c.outcome() != Check.Outcome.NOT_APPLICABLE) {
                        out.println("  " + c);
                    }
                }
                Acceptance a = acceptance(report);
                out.println("acceptance: " + a.verdict() + (a.reasons().isEmpty() ? "" : " " + a.reasons()));
                return a.exit();
            }
            default -> {
                return EXIT_USAGE;
            }
        }
        return EXIT_OK;
    }

    /** The acceptance decision of a verify run: defects first, then incompleteness; never a count of zeros. */
    public record Acceptance(int exit, String verdict, List<String> reasons) {
    }

    public static Acceptance acceptance(OracleReport report) {
        List<String> reasons = new ArrayList<>();
        if (!report.defects().isEmpty()) {
            for (Check c : report.defects()) {
                reasons.add(c.toString());
            }
            return new Acceptance(EXIT_DEFECTS, "DEFECTIVE", reasons);
        }
        for (String missing : report.missingRequired()) {
            reasons.add("required check missing from the report: " + missing);
        }
        for (Check c : report.checks()) {
            if (c.outcome() == Check.Outcome.UNRESOLVED) {
                reasons.add(c.toString());
            }
            else if (c.outcome() == Check.Outcome.UNSUPPORTED && !OPTIONAL_PROPERTIES.contains(c.property())) {
                reasons.add(c.toString());
            }
            else if (c.outcome() == Check.Outcome.NOT_APPLICABLE && "whole_record.excluded".equals(c.property())) {
                reasons.add("declared exclusion waives acquired records: " + c);
            }
        }
        if (!reasons.isEmpty()) {
            return new Acceptance(EXIT_INCOMPLETE, "INCOMPLETE", reasons);
        }
        return new Acceptance(EXIT_OK, "ACCEPTED", reasons);
    }

    private static void write(Json json, Path out, PrintStream console) throws IOException {
        String text = PRETTY.writeValueAsString(PRETTY.readTree(NeutralJson.write(json)));
        if (out == null) {
            console.println(text);
        }
        else {
            Files.writeString(out, text, StandardCharsets.UTF_8);
            console.println("wrote " + out);
        }
    }
}
