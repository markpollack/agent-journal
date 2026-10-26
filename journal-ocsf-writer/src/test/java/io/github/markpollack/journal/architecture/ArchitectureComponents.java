package io.github.markpollack.journal.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The analyzed scope of the architecture checks and the one grouping of its classes into the components the architecture
 * guide ({@code docs/architecture/}) names. The scope is the production bytecode of {@code journal-ocsf-writer} and
 * {@code journal-capture-tck}, imported from this module's test classpath, the one classpath that holds both. Test classes are
 * excluded; nested and anonymous classes belong to their top-level class.
 * <p>Membership is declared here once, in the order the guide prefers: a package root, implementation of a declared interface,
 * then an explicit list. {@link ComponentViewTest} checks that every analyzed class belongs to exactly one component, so a new
 * class cannot drop out of the documentation unnoticed, and draws the extracted view from this grouping;
 * {@link ArchitectureBoundaryTest} uses the same packages for its rules. The responsibilities are authored summaries of the
 * classes' own documentation, not extracted facts.
 */
final class ArchitectureComponents {

    static final String WRITER = "io.github.markpollack.journal.ocsf.writer";
    static final String TCK = "io.github.markpollack.journal.tck";
    static final String WRITER_MODULE = "journal-ocsf-writer";
    static final String TCK_MODULE = "journal-capture-tck";

    /**
     * One documentation component.
     * @param id stable short id used in the generated diagram source
     * @param name the name the guide uses
     * @param module the Maven module whose main code holds the classes
     * @param responsibility an authored one-sentence summary of the classes' documented responsibility
     * @param membership how membership is decided, in words (written into the generated facts)
     * @param members the membership predicate over top-level classes
     */
    record Component(String id, String name, String module, String responsibility, String membership, Predicate<JavaClass> members) {
    }

    /**
     * A data flow through a file, which no bytecode dependency shows: the writer and the checker share no code, only bytes on
     * disk. Declared from the named class's documentation; drawn dashed and labelled as declared, never as extracted.
     * @param from a component id or an artifact id
     * @param to a component id or an artifact id
     * @param label the arrow label
     * @param basis the class whose documentation states the flow
     */
    record DeclaredFlow(String from, String to, String label, String basis) {
    }

    /** A file the declared flows pass through (not code). */
    record Artifact(String id, String name) {
    }

    static final List<Component> COMPONENTS = List.of(
            writer("capture", "Capture and replay entry points",
                    "Acquires a CLI's native output (launching the CLI, reading standard input, or replaying a recording) and feeds it, with arrival times, to the run writer; carries the process outcome and archives the native session file unchanged.",
                    named("CliCapture", "LiveCapture", "OcsfWriterTool", "Replay")),
            writer("run-writer", "Run writer",
                    "Records one run: accepts each declared source, frames it, selects its compatibility entry, hands each record to the source's mapper and routes the resulting events to the root core or a child core; owns flush, complete, interrupt and failure.",
                    named("OcsfRunWriter")),
            writer("run-input", "Source input and run facts",
                    "The capture-facing input interface (byte chunks or whole records, each with the arrival time the caller observed) and the facts declared before input: the run, each source's identity and route, and writer settings.",
                    named("SourceInput", "SourceDescriptor", "RunDescriptor", "WriterOptions")),
            writer("framing", "Framing and native records",
                    "Splits arbitrary byte chunks into lines and classifies each line before interpretation (parsed, malformed, undecodable, blank, incomplete); nothing is dropped.",
                    named("LineFramer", "NativeRecord")),
            writer("compatibility", "Compatibility selection",
                    "Chooses at most one registry entry (source description, mapping contract, Java mapper) per source from the CLI, profile, route and version, reading only the version-bearing record before selection; no match means the input is retained, not interpreted.",
                    named("Compatibility", "VersionBootstrap")),
            writer("mapping", "Source mappers",
                    "Interpret one native record at a time under the selected mapping contract into OCSF-extension events or whole-record retention, naming the contract rule each event executes.",
                    implementing(WRITER + ".SourceMapper").or(named("SourceMapper", "MapperSupport", "SourceContext", "CodexItems", "UsageTranches", "MappingContracts",
                            "ClaudeMcp", "ClaudeChildUsage", "AntigravityMcp", "McpObservations", "CopilotTools", "CopilotAccounting", "CopilotDefinitions", "JunieUsage", "SourceMappers", "ClaudeDefinitions", "ExecutionMetrics", "ReportedQuantities", "ExecutionState").in(WRITER)),
                    "classes implementing SourceMapper, plus SourceMapper, MapperSupport, SourceContext, CodexItems, UsageTranches, MappingContracts, ClaudeMcp, ClaudeChildUsage, AntigravityMcp, McpObservations, "
                            + "CopilotTools, CopilotAccounting, CopilotDefinitions, JunieUsage, SourceMappers, ClaudeDefinitions, ExecutionMetrics, ReportedQuantities, ExecutionState"),
            writer("final-assembly", "Final assembly",
                    "Assembles the declared sources of one execution under a reviewed profile into one new events.jsonl: holds each mapper's observations, gates sources on the identities they report, "
                            + "groups observations by declared joins, consolidates each multi-source entity field by field with its evidence and conflicts, and declares the assembly.",
                    named("FinalAssembly", "Assembler", "AssemblyProfile", "AssemblyPlan", "AssemblyGate", "AssemblyGrouping", "EventMerge", "AssemblyDeclaration")),
            writer("mcp-binding", "MCP protocol binding",
                    "Generates the typed mcp_* objects and the binding table from the pinned official MCP JSON Schemas, embeds a protocol object or a CLI's partial projection into them losslessly, and names the official definitions a file must declare to be read offline.",
                    named("McpBinding", "McpEmbedder", "McpAlternatives", "McpNode", "McpSchemaModel", "McpUnionMatch", "McpAllOf", "McpBindingNames", "McpBindingGenerator",
                            "McpGeneratedFiles", "McpCoverage")),
            writer("persistence", "Core persistence",
                    "Writes each core's self-describing events.jsonl (declarations before the events that need them, then events) and its ancillary run.json, keeping a readable prefix at every flush and hashing what is on disk at finalization.",
                    named("CoreWriter", "Declarations", "DefinitionClosure", "ProtocolDeclarations")),
            writer("schema", "Schema and toolchain pins",
                    "The pinned compiled OCSF schema (class and category ids are read, never hand-written) and the checksummed toolchain every package names.",
                    named("SchemaPin", "Pins", "SchemaExcerpt")),
            writer("json-writer", "Lexically exact JSON (writer)",
                    "The writer's own JSON value: numbers keep their spelling, member order is kept, duplicates are rejected; Jackson's tokenizer does the lexing and nothing else of Jackson is used.",
                    named("Jv")),
            tck("reader", "Package reader",
                    "Opens a run package from events.jsonl alone (or its directory), verifies pins and hashes, and exposes declarations, events, children and explicit diagnostics; it never fetches a registry or loads production classes.",
                    named("ocsf.PackageReader", "ocsf.DeclaredFile", "ocsf.OcsfPackage", "ocsf.RunManifest", "ocsf.Event", "ocsf.Diagnostic", "ocsf.SchemaCatalog", "ocsf.CompatibilityHistory",
                            "ocsf.SemanticContract", "ocsf.SemanticOperation", "ocsf.McpProtocol", "schema.McpValidation")),
            tck("oracle", "Independent oracle",
                    "Registers independent expected facts and extracts actual assertions; executes attributed value, identity, preservation, origin and final-selection comparisons, retaining missing, ambiguous and unsupported outcomes.",
                    named("ocsf.OcsfOracle", "ocsf.OracleReport", "ocsf.ExpectedSources", "ocsf.Reconstruction", "ocsf.McpChecks", "ocsf.AssemblyEvidence", "ocsf.AssertionAccounting", "ocsf.AttributedFacts", "ocsf.EventOrigin", "ocsf.FactComparison", "ocsf.FinalComparisons", "ocsf.FinalIdentity", "ocsf.FinalRelation", "ocsf.FinalSelection", "ocsf.OriginComparisons", "ocsf.ProtocolComparisons", "ocsf.SourceReferences", "ocsf.UsageComparison", "ocsf.UsageComparisons")),
            tck("profiles", "Source profiles",
                    "State, from a native artifact's records alone and independently of the writer's mappers, which facts it reports: the expected population and exact values per fact family.",
                    implementing(TCK + ".ocsf.SourceProfile").or(named("ocsf.SourceProfile", "ocsf.ExpectedFacts", "ocsf.TrancheReading", "ocsf.McpReadings", "ocsf.ClaudeChildUsageReading", "ocsf.AntigravityNativeDetails", "ocsf.ClaudeNativeDetails", "ocsf.CodexNativeDetails", "ocsf.CopilotRedundancy", "ocsf.CopilotTelemetryReading", "ocsf.CopilotToolReading", "ocsf.CopilotUsageReading", "ocsf.ExecutionMetricReading", "ocsf.ReportedQuantityReading", "ocsf.ExecutionStateReading", "ocsf.FamilyFacts", "ocsf.NativeContent", "ocsf.NativeDetails", "ocsf.NativeRedundancy", "ocsf.NativeRule", "ocsf.ResponseFacts", "ocsf.SourceBindings", "ocsf.SourceFacts", "ocsf.SourceReaders", "ocsf.SourceVariant").in(TCK)),
                    "classes implementing SourceProfile, expected-fact builders, exact source bindings, native detail readers and redundancy checks"),
            tck("views", "Analysis views",
                    "Compute the named deterministic operations a package's semantic contract declares from the saved core alone, and measure a core's bytes.",
                    named("ocsf.AnalysisViews", "ocsf.PackageMeasure")),
            tck("reader-tool", "Reader command line",
                    "The inspect, views, measure, reconstruct, self-contained and verify commands, with exit statuses that keep refusal, defects and incompleteness apart.",
                    named("ocsf.OcsfReaderTool")),
            tck("neutral", "Neutral JSON, bytes and check ledger",
                    "Vendor-neutral reading: JSON through Jackson's tokenizer only with exact numbers, native artifacts read as bytes first with every line kept, hashing, and the check outcomes and ledger every verifier reports with.",
                    named("verify.Json", "verify.NeutralJson", "verify.Difference", "verify.Check", "verify.CheckLedger", "verify.CheckReport", "ocsf.NativeArtifact")),
            tck("legacy-verifier", "Earlier-format Claude stream verifier",
                    "Verifies packages of the earlier journal-core format produced by claude-code-capture against their Claude stream; not on the OCSF writer's path.",
                    named("verify.CandidatePackage", "verify.ClaudeStreamContract", "verify.ClaudeStreamOracle", "verify.ClaudeStreamVerifier", "verify.VerificationReport")),
            tck("inventory", "Native inventory tools",
                    "Empirical variant/path inventories of native artifacts and their comparison with the accepted source specifications (findings are proposals, never applied); "
                            + "source joins and the independent assembly oracle, which judges a finally assembled file from native identities alone.",
                    inPackage(TCK + ".ocsf.inventory"), "package " + TCK + ".ocsf.inventory"),
            tck("prototype", "Prototype candidate support",
                    "NON-PRODUCTION support that produces candidate packages from the earlier Python prototype's layout; it is producer-side code and the oracle never uses it.",
                    inPackage(TCK + ".ocsf.prototype"), "package " + TCK + ".ocsf.prototype"),
            tck("venn", "Value-landing comparison",
                    "Counts which native values reappear anywhere in a journal record, read from the data rather than parser code; an exploratory measure, not an oracle check.",
                    inPackage(TCK + ".venn"), "package " + TCK + ".venn"));

    static final List<Artifact> ARTIFACTS = List.of(
            new Artifact("native", "native artifact\\n(the CLI's bytes, and an\\nindependent byte copy)"),
            new Artifact("package", "run package on disk\\nevents.jsonl + run.json"),
            new Artifact("expected", "expected-sources.json\\n(frozen verification\\ndenominator)"));

    static final List<DeclaredFlow> DECLARED_FLOWS = List.of(
            new DeclaredFlow("native", "capture", "acquired by", WRITER + ".CliCapture"),
            new DeclaredFlow("persistence", "package", "writes", WRITER + ".CoreWriter"),
            new DeclaredFlow("package", "reader", "read by", TCK + ".ocsf.PackageReader"),
            new DeclaredFlow("native", "oracle", "copy read as bytes by", TCK + ".ocsf.OcsfOracle"),
            new DeclaredFlow("capture", "expected", "freezes after a live launch", WRITER + ".CliCapture#freezeExpectedSources"),
            new DeclaredFlow("expected", "oracle", "denominator for", TCK + ".ocsf.ExpectedSources"));

    /** Components drawn as boxes without their incoming edges: shared infrastructure every component of its module uses. */
    static final Set<String> SHARED = Set.of("json-writer", "neutral");

    /** Components left out of the drawn view (not on the writer-to-oracle path); their classes and edges stay in the facts. */
    static final Set<String> NOT_DRAWN = Set.of("legacy-verifier", "inventory", "prototype", "venn");

    private ArchitectureComponents() {
    }

    /** The analyzed scope: production classes of both modules, test classes excluded. */
    static JavaClasses importScope() {
        return new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(WRITER, TCK);
    }

    static JavaClass topLevel(JavaClass c) {
        JavaClass t = c;
        while (t.getEnclosingClass().isPresent()) {
            t = t.getEnclosingClass().get();
        }
        return t;
    }

    /** The components a top-level class belongs to (exactly one when the grouping is complete). */
    static List<Component> componentsOf(JavaClass topLevel) {
        return COMPONENTS.stream().filter(c -> c.members().test(topLevel)).toList();
    }

    static Component component(String id) {
        return COMPONENTS.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /** The class name relative to its module's root package, as the generated facts print it. */
    static String shortName(JavaClass c) {
        String name = c.getName();
        if (name.startsWith(WRITER + ".")) {
            return name.substring(WRITER.length() + 1);
        }
        return name.startsWith(TCK + ".") ? name.substring(TCK.length() + 1) : name;
    }

    private static Component writer(String id, String name, String responsibility, Named members) {
        return new Component(id, name, WRITER_MODULE, responsibility, "named classes in " + WRITER + ": " + String.join(", ", members.names()), members.in(WRITER));
    }

    private static Component writer(String id, String name, String responsibility, Predicate<JavaClass> members, String membership) {
        return new Component(id, name, WRITER_MODULE, responsibility, membership, inPackage(WRITER).and(members));
    }

    private static Component tck(String id, String name, String responsibility, Named members) {
        return new Component(id, name, TCK_MODULE, responsibility, "named classes in " + TCK + ": " + String.join(", ", members.names()), members.in(TCK));
    }

    private static Component tck(String id, String name, String responsibility, Predicate<JavaClass> members, String membership) {
        return new Component(id, name, TCK_MODULE, responsibility, membership, members);
    }

    /** An explicit list of class names relative to a root package. */
    record Named(List<String> names) {

        Predicate<JavaClass> in(String root) {
            Set<String> full = Set.copyOf(names.stream().map(n -> root + "." + n).toList());
            return c -> full.contains(c.getName());
        }
    }

    private static Named named(String... names) {
        return new Named(Arrays.asList(names));
    }

    private static Predicate<JavaClass> inPackage(String pkg) {
        return c -> c.getPackageName().equals(pkg);
    }

    private static Predicate<JavaClass> implementing(String type) {
        return c -> !c.getName().equals(type) && c.isAssignableTo(type);
    }
}
