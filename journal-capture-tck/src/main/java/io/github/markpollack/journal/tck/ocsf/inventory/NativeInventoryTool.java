package io.github.markpollack.journal.tck.ocsf.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line entry point of the independent raw-record inventory:
 *
 * <pre>
 *   inventory &lt;profile&gt; &lt;artifact&gt; [out.json]                      the empirical variant/path/kind inventory of one native artifact
 *   drift &lt;spec.json&gt; &lt;artifact&gt; [report.json]                      the inventory compared with Journal's accepted specification (findings are proposals, never applied)
 *   external &lt;schema.json&gt; &lt;discriminator&gt; &lt;profile&gt; &lt;artifact&gt; [report.json]
 *                                                                    the inventory compared with an external JSON Schema description (cross-check only)
 *   schema &lt;spec.json&gt; [out.json]                                    the JSON Schema derived from the accepted specification
 *   draft &lt;profile&gt; &lt;specification-version&gt; &lt;out.json&gt; &lt;artifact&gt;...  a DRAFT specification from the inventories of the given artifacts
 *   overlap &lt;join-spec.json&gt; &lt;a[,a...]&gt; &lt;b[,b...]&gt; [report.json]       which facts two sources share, by reported identities only (EXPERIMENTAL; {@link SourceJoin})
 * </pre>
 *
 * Exit status: 0 when the command ran ({@code drift} additionally exits {@link #EXIT_DRIFT} 5 when shape drift was
 * found, so a check can block on it); {@link #EXIT_USAGE} 64 on bad arguments. Reports hold paths, kinds, counts and
 * record indexes, never record content.
 */
public final class NativeInventoryTool {

    public static final int EXIT_OK = 0;
    public static final int EXIT_DRIFT = 5;
    public static final int EXIT_USAGE = 64;

    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private NativeInventoryTool() {
    }

    @SuppressWarnings("SystemOut") // a command-line tool: its standard output and error are its output
    public static void main(String[] args) throws IOException {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length < 3) {
            usage(err);
            return EXIT_USAGE;
        }
        switch (args[0]) {
            case "inventory" -> {
                Json inv = inventory(args[1], Path.of(args[2]));
                write(inv, args.length > 3 ? Path.of(args[3]) : null, out);
                out.println("inventory: " + ((Json.JObject) inv.member("variants")).members().size() + " variant(s), " + inv.member("records").scalarText() + " record(s)");
                return EXIT_OK;
            }
            case "drift" -> {
                Json spec = SourceSpecification.read(Path.of(args[1]));
                Json inv = inventory(RawInventory.text(spec, "profile"), Path.of(args[2]));
                Json report = InventoryComparison.compare(spec, inv);
                write(report, args.length > 3 ? Path.of(args[3]) : null, out);
                boolean drift = report.member("shape_drift") instanceof Json.JBool b && b.value();
                out.println("drift: " + report.member("proposals").scalarText() + " proposal(s), " + report.member("notes").scalarText() + " note(s); shape_drift " + drift);
                return drift ? EXIT_DRIFT : EXIT_OK;
            }
            case "external" -> {
                if (args.length < 5) {
                    usage(err);
                    return EXIT_USAGE;
                }
                Json schema = NeutralJson.readDocument(Path.of(args[1]));
                Json inv = inventory(args[3], Path.of(args[4]));
                Json report = ExternalSchemaPaths.compare(schema, args[2], inv, args[2], Path.of(args[1]).getFileName().toString());
                write(report, args.length > 5 ? Path.of(args[5]) : null, out);
                out.println("external: " + report.member("variants_described").scalarText() + " variant(s) described, " + report.member("variants_not_described").scalarText()
                        + " not described, " + report.member("observed_paths_not_declared").scalarText() + " observed path(s) not declared");
                return EXIT_OK;
            }
            case "schema" -> {
                Json spec = SourceSpecification.read(Path.of(args[1]));
                write(SourceSpecification.jsonSchema(spec), args.length > 2 ? Path.of(args[2]) : null, out);
                return EXIT_OK;
            }
            case "overlap" -> {
                if (args.length < 4) {
                    usage(err);
                    return EXIT_USAGE;
                }
                Json report = SourceJoin.join(NeutralJson.readDocument(Path.of(args[1])), artifacts(args[2]), artifacts(args[3]));
                write(report, args.length > 4 ? Path.of(args[4]) : null, out);
                StringBuilder line = new StringBuilder("overlap: session " + report.path("session", "status").text());
                for (Json k : report.member("keys").elements()) {
                    line.append("; ").append(k.member("name").text()).append(' ').append(k.member("status").text());
                    if (k.member("matched") != null) {
                        line.append(" matched ").append(k.member("matched").scalarText());
                    }
                }
                out.println(line);
                return EXIT_OK;
            }
            case "draft" -> {
                if (args.length < 5) {
                    usage(err);
                    return EXIT_USAGE;
                }
                List<Json> inventories = new ArrayList<>();
                for (int i = 4; i < args.length; i++) {
                    inventories.add(inventory(args[1], Path.of(args[i])));
                }
                Json draft = SourceSpecification.draft(args[1], args[2], null, null, List.of(), null, inventories);
                write(draft, Path.of(args[3]), out);
                return EXIT_OK;
            }
            default -> {
                usage(err);
                return EXIT_USAGE;
            }
        }
    }

    private static List<NativeArtifact> artifacts(String commaSeparated) {
        List<NativeArtifact> out = new ArrayList<>();
        for (String p : commaSeparated.split(",")) {
            out.add(NativeArtifact.read(Path.of(p)));
        }
        return out;
    }

    static Json inventory(String profile, Path artifact) {
        NativeArtifact art = RawInventory.DOCUMENT_PROFILES.contains(profile) ? NativeArtifact.document(artifact) : NativeArtifact.read(artifact);
        return RawInventory.inventory(art, artifact.getFileName().toString(), profile);
    }

    private static void usage(PrintStream err) {
        err.println("usage: inventory <profile> <artifact> [out.json] | drift <spec.json> <artifact> [report.json] | external <schema.json> <discriminator> <profile> <artifact> [report.json]"
                + " | schema <spec.json> [out.json] | draft <profile> <specification-version> <out.json> <artifact>..."
                + " | overlap <join-spec.json> <a[,a...]> <b[,b...]> [report.json]");
    }

    private static void write(Json json, Path out, PrintStream console) throws IOException {
        String text = PRETTY.writeValueAsString(PRETTY.readTree(NeutralJson.write(json)));
        if (out == null) {
            console.println(text);
        }
        else {
            Files.writeString(out, text + "\n", StandardCharsets.UTF_8);
            console.println("wrote " + out);
        }
    }
}
