package io.github.markpollack.journal.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import io.github.markpollack.journal.architecture.ArchitectureComponents.Artifact;
import io.github.markpollack.journal.architecture.ArchitectureComponents.Component;
import io.github.markpollack.journal.architecture.ArchitectureComponents.DeclaredFlow;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.github.markpollack.journal.architecture.ArchitectureComponents.COMPONENTS;
import static io.github.markpollack.journal.architecture.ArchitectureComponents.NOT_DRAWN;
import static io.github.markpollack.journal.architecture.ArchitectureComponents.SHARED;
import static io.github.markpollack.journal.architecture.ArchitectureComponents.componentsOf;
import static io.github.markpollack.journal.architecture.ArchitectureComponents.shortName;
import static io.github.markpollack.journal.architecture.ArchitectureComponents.topLevel;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The extracted component view: every production class of the writer and the TCK assigned to exactly one documentation
 * component ({@link ArchitectureComponents}), and every component-to-component dependency that a direct bytecode reference
 * between their classes establishes (ArchUnit's dependencies from self: calls, field access, construction, inheritance,
 * member and parameter types, annotations). Writes three files under {@code docs/architecture/generated/}: the facts with
 * their supporting class pairs ({@code component-view.json}), a readable table ({@code component-view.md}) and the diagram
 * source ({@code component-view.puml}).
 * <p>The build checks that the committed files equal what the current classes yield, so the view cannot silently go stale;
 * {@code -Darchitecture.regenerate=true} (or {@code scripts/architecture-docs.sh}) rewrites them. The output depends only on
 * the compiled classes and the grouping, never on time or the machine.
 */
class ComponentViewTest {

    static final Path GENERATED = Path.of("..", "docs", "architecture", "generated").toAbsolutePath().normalize();

    /** One extracted dependency between two components, with the class pairs that establish it. */
    record Edge(String from, String to, TreeSet<String> classPairs) {
    }

    @Test
    void everyAnalyzedClassBelongsToExactlyOneComponent() {
        List<String> problems = new ArrayList<>();
        for (JavaClass c : topLevelClasses(ArchitectureComponents.importScope())) {
            List<Component> in = componentsOf(c);
            if (in.size() != 1) {
                problems.add(c.getName() + " belongs to " + in.stream().map(Component::id).toList());
            }
        }
        assertThat(problems).as("add each new class to one component in ArchitectureComponents").isEmpty();
        assertThat(COMPONENTS).allSatisfy(comp -> assertThat(members(comp)).as(comp.id() + " has members").isNotEmpty());
    }

    @Test
    void theCommittedComponentViewIsWhatTheCurrentClassesYield() throws IOException {
        JavaClasses scope = ArchitectureComponents.importScope();
        List<Edge> edges = edges(scope);
        Map<String, TreeSet<String>> external = externalPackages(scope);
        assertThat(edges).as("the writer and the checker share no code").noneMatch(e -> !module(e.from()).equals(module(e.to())));
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("component-view.json", json(scope, edges, external));
        outputs.put("component-view.md", markdown(scope, edges, external));
        outputs.put("component-view.puml", plantUml(edges));
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, String> o : outputs.entrySet()) {
            Path file = GENERATED.resolve(o.getKey());
            if (Boolean.getBoolean("architecture.regenerate")) {
                Files.createDirectories(GENERATED);
                Files.writeString(file, o.getValue(), StandardCharsets.UTF_8);
            }
            else if (!Files.exists(file) || !Files.readString(file, StandardCharsets.UTF_8).equals(o.getValue())) {
                stale.add(file.toString());
            }
        }
        assertThat(stale).as("the classes changed; regenerate with scripts/architecture-docs.sh (or -Darchitecture.regenerate=true) and review the diff").isEmpty();
    }

    // ---- extraction -------------------------------------------------------------------------------------------------

    static List<JavaClass> topLevelClasses(JavaClasses scope) {
        return scope.stream().filter(c -> topLevel(c).equals(c)).sorted((a, b) -> a.getName().compareTo(b.getName())).toList();
    }

    static List<String> members(Component comp) {
        return topLevelClasses(ArchitectureComponents.importScope()).stream().filter(c -> comp.members().test(c)).map(ArchitectureComponents::shortName).toList();
    }

    static String componentId(JavaClass c) {
        List<Component> in = componentsOf(topLevel(c));
        return in.size() == 1 ? in.get(0).id() : null;
    }

    static List<Edge> edges(JavaClasses scope) {
        Map<String, Edge> byKey = new TreeMap<>();
        for (JavaClass c : scope) {
            String from = componentId(c);
            for (Dependency d : c.getDirectDependenciesFromSelf()) {
                JavaClass target = d.getTargetClass().getBaseComponentType();
                if (!scope.contain(target.getName())) {
                    continue;
                }
                String to = componentId(target);
                if (from != null && to != null && !from.equals(to)) {
                    byKey.computeIfAbsent(order(from) + ":" + order(to), k -> new Edge(from, to, new TreeSet<>()))
                            .classPairs().add(shortName(topLevel(c)) + " -> " + shortName(topLevel(target)));
                }
            }
        }
        return List.copyOf(byKey.values());
    }

    /** The non-JDK packages each component's classes refer to, outside the analyzed scope. */
    static Map<String, TreeSet<String>> externalPackages(JavaClasses scope) {
        Map<String, TreeSet<String>> out = new TreeMap<>();
        for (JavaClass c : scope) {
            for (Dependency d : c.getDirectDependenciesFromSelf()) {
                JavaClass target = d.getTargetClass().getBaseComponentType();
                if (!target.isPrimitive() && !scope.contain(target.getName()) && !target.getPackageName().startsWith("java.")) {
                    out.computeIfAbsent(componentId(c), k -> new TreeSet<>()).add(target.getPackageName());
                }
            }
        }
        return out;
    }

    private static String order(String id) {
        int i = COMPONENTS.indexOf(ArchitectureComponents.component(id));
        return String.format("%02d", i);
    }

    private static String module(String id) {
        return ArchitectureComponents.component(id).module();
    }

    private static boolean drawn(Edge e) {
        return !SHARED.contains(e.to()) && !NOT_DRAWN.contains(e.from()) && !NOT_DRAWN.contains(e.to());
    }

    // ---- rendering --------------------------------------------------------------------------------------------------

    static String json(JavaClasses scope, List<Edge> edges, Map<String, TreeSet<String>> external) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append(" \"kind\": \"agent-journal/architecture-component-view\",\n");
        sb.append(" \"format_version\": \"0.1\",\n");
        sb.append(" \"status\": \"extracted structure (components and dependencies); declared flows and drawing choices are labelled\",\n");
        sb.append(" \"produced_by\": \"ComponentViewTest (journal-ocsf-writer test scope) with ArchUnit 1.5.0 over compiled production classes\",\n");
        sb.append(" \"scope\": {\"packages\": [").append(q(ArchitectureComponents.WRITER)).append(", ").append(q(ArchitectureComponents.TCK))
                .append("], \"classes\": \"production classes only (test classes excluded); nested and anonymous classes belong to their top-level class\", \"top_level_classes\": ")
                .append(topLevelClasses(scope).size()).append("},\n");
        sb.append(" \"relationship\": \"A depends on B when a class of A refers directly to a class of B in bytecode: a call, field access, construction, inheritance, member or parameter type, or annotation. It is a static reference, not evidence that the path executed.\",\n");
        sb.append(" \"components\": [\n");
        for (int i = 0; i < COMPONENTS.size(); i++) {
            Component c = COMPONENTS.get(i);
            sb.append("  {\"id\": ").append(q(c.id())).append(", \"name\": ").append(q(c.name())).append(", \"module\": ").append(q(c.module()))
                    .append(",\n   \"membership\": ").append(q(c.membership())).append(",\n   \"responsibility\": ").append(q(c.responsibility()))
                    .append(",\n   \"classes\": ").append(list(members(c))).append(",\n   \"external_packages\": ").append(list(List.copyOf(external.getOrDefault(c.id(), new TreeSet<>()))))
                    .append("}").append(i < COMPONENTS.size() - 1 ? ",\n" : "\n");
        }
        sb.append(" ],\n \"dependencies\": [\n");
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            sb.append("  {\"from\": ").append(q(e.from())).append(", \"to\": ").append(q(e.to())).append(", \"drawn\": ").append(drawn(e))
                    .append(",\n   \"class_pairs\": ").append(list(List.copyOf(e.classPairs()))).append("}").append(i < edges.size() - 1 ? ",\n" : "\n");
        }
        sb.append(" ],\n \"declared_flows\": [\n");
        List<DeclaredFlow> flows = ArchitectureComponents.DECLARED_FLOWS;
        for (int i = 0; i < flows.size(); i++) {
            DeclaredFlow f = flows.get(i);
            sb.append("  {\"from\": ").append(q(f.from())).append(", \"to\": ").append(q(f.to())).append(", \"label\": ").append(q(f.label()))
                    .append(", \"status\": \"declared design (from the documentation of the basis class), not extracted\", \"basis\": ").append(q(f.basis())).append("}")
                    .append(i < flows.size() - 1 ? ",\n" : "\n");
        }
        sb.append(" ],\n \"drawing\": {\"components_not_drawn\": ").append(list(List.copyOf(new TreeSet<>(NOT_DRAWN))))
                .append(", \"incoming_edges_not_drawn_to\": ").append(list(List.copyOf(new TreeSet<>(SHARED))))
                .append(", \"reason\": \"presentation only: the omitted components are not on the writer-to-oracle path, and the shared JSON components are used across their module; every omitted edge is listed above\"}\n}\n");
        return sb.toString();
    }

    static String markdown(JavaClasses scope, List<Edge> edges, Map<String, TreeSet<String>> external) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- GENERATED by ComponentViewTest from compiled classes; do not edit. Regenerate with scripts/architecture-docs.sh. -->\n\n");
        sb.append("# Component view: extracted facts\n\n");
        sb.append("**Status:** extracted structure. Membership is the declared grouping in `ArchitectureComponents`; each dependency below is a direct bytecode reference ")
                .append("found by ArchUnit 1.5.0 in the production classes of `journal-ocsf-writer` and `journal-capture-tck` (")
                .append(topLevelClasses(scope).size()).append(" top-level classes, test classes excluded). A static reference is not evidence that a path executed. ")
                .append("Responsibilities are authored summaries of each class's own documentation. The diagram is [`component-view.svg`](component-view.svg); its source is [`component-view.puml`](component-view.puml) and the machine-readable facts are [`component-view.json`](component-view.json).\n\n");
        sb.append("## Components\n\n| Component | Module | Responsibility | Classes | Libraries used |\n|---|---|---|---|---|\n");
        for (Component c : COMPONENTS) {
            sb.append("| ").append(c.name()).append(NOT_DRAWN.contains(c.id()) ? " *(not drawn)*" : "").append(" | `").append(c.module()).append("` | ")
                    .append(c.responsibility()).append(" | ").append(String.join(", ", members(c).stream().map(n -> "`" + n + "`").toList())).append(" | ")
                    .append(String.join(", ", external.getOrDefault(c.id(), new TreeSet<>()).stream().map(n -> "`" + n + "`").toList())).append(" |\n");
        }
        sb.append("\n## Dependencies between components\n\nEach row is one extracted component dependency and the class pairs that establish it. ")
                .append("Rows marked *(not drawn)* are omitted from the diagram for readability only.\n\n| From | To | Class pairs |\n|---|---|---|\n");
        for (Edge e : edges) {
            sb.append("| ").append(ArchitectureComponents.component(e.from()).name()).append(" | ").append(ArchitectureComponents.component(e.to()).name())
                    .append(drawn(e) ? "" : " *(not drawn)*").append(" | ").append(String.join("<br>", e.classPairs().stream().map(p -> "`" + p + "`").toList())).append(" |\n");
        }
        sb.append("\nNo row connects a writer component with a TCK component in either direction: the two modules meet only in files on disk, the run package and, after a live launch, ")
                .append("the expected-sources manifest (declared flows, dashed in the diagram). `ArchitectureBoundaryTest` enforces the code separation as declared policy.\n");
        return sb.toString();
    }

    static String plantUml(List<Edge> edges) {
        StringBuilder sb = new StringBuilder("@startuml\n");
        sb.append("' GENERATED by ComponentViewTest from compiled classes (ArchUnit 1.5.0); do not edit. Regenerate with scripts/architecture-docs.sh.\n");
        sb.append("!pragma layout smetana\n");
        sb.append("skinparam shadowing false\nskinparam defaultFontName Helvetica\nskinparam defaultFontSize 13\nskinparam componentStyle rectangle\n");
        sb.append("skinparam ArrowColor #444444\nskinparam ComponentBorderColor #444444\nskinparam ComponentBackgroundColor #FFFFFF\nskinparam ArtifactBackgroundColor #F4F4F4\nskinparam PackageBorderColor #888888\n");
        sb.append("title Writer and independent checker: extracted component dependencies\n\n");
        sb.append("artifact \"").append(ArchitectureComponents.ARTIFACTS.get(0).name()).append("\" as native\n");
        for (String module : List.of(ArchitectureComponents.WRITER_MODULE, ArchitectureComponents.TCK_MODULE)) {
            sb.append("package \"").append(module).append(module.equals(ArchitectureComponents.WRITER_MODULE) ? "\\n(production writer)" : "\\n(independent reader and oracle)").append("\" {\n");
            for (Component c : COMPONENTS) {
                if (c.module().equals(module) && !NOT_DRAWN.contains(c.id())) {
                    sb.append("  [").append(c.name()).append("] as ").append(alias(c.id())).append(SHARED.contains(c.id()) ? " #EEEEEE" : "").append("\n");
                }
            }
            sb.append("}\n");
        }
        for (Artifact a : ArchitectureComponents.ARTIFACTS.subList(1, ArchitectureComponents.ARTIFACTS.size())) {
            sb.append("artifact \"").append(a.name()).append("\" as ").append(alias(a.id())).append("\n");
        }
        sb.append("\n' extracted: a class of the source component refers to a class of the target component\n");
        for (Edge e : edges) {
            if (drawn(e)) {
                sb.append(alias(e.from())).append(" --> ").append(alias(e.to())).append("\n");
            }
        }
        sb.append("\n' declared data flows through files (not bytecode dependencies)\n");
        for (DeclaredFlow f : ArchitectureComponents.DECLARED_FLOWS) {
            sb.append(alias(f.from())).append(" ..> ").append(alias(f.to())).append(" : ").append(f.label()).append("\n");
        }
        sb.append("\nlegend bottom left\n");
        sb.append("  Solid arrow: extracted static dependency (a class of one component refers to a class of the other).\n");
        sb.append("  Dashed arrow: declared data flow through a file; no code connects the two modules.\n");
        sb.append("  Grey boxes: shared JSON, byte and check-ledger code used across their module (incoming arrows omitted).\n");
        sb.append("  Not drawn: ").append(String.join(", ", NOT_DRAWN.stream().sorted().map(id -> ArchitectureComponents.component(id).name()).toList())).append(".\n");
        sb.append("  Every arrow and omission is listed in component-view.md with its class pairs.\n");
        sb.append("endlegend\n@enduml\n");
        return sb.toString();
    }

    private static String alias(String id) {
        return id.replace('-', '_');
    }

    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(ch);
            }
        }
        return sb.append('"').toString();
    }

    private static String list(List<String> items) {
        return "[" + String.join(", ", items.stream().map(ComponentViewTest::q).toList()) + "]";
    }
}
