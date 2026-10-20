package io.github.markpollack.journal.tck.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The candidate: a persisted Journal run package read directly from {@code run.json} and {@code events.jsonl} with
 * {@link NeutralJson}, plus the child runs linked to it. By construction it reads nothing else: not {@code raw/},
 * not {@code analysis.jsonl}, not {@code feedback.jsonl}, and no production class interprets a value. The files it
 * read are listed so a report can show the candidate's denominator.
 */
public final class CandidatePackage {

    /** One event line. */
    public record Event(int lineNumber, Json json) {
        public String type() {
            return ClaudeStreamOracle.textOf(json, "@type");
        }

        public String name() {
            return ClaudeStreamOracle.textOf(json, "name");
        }

        public Json attributes() {
            return json.path("attributes");
        }

        public Json attribute(String key) {
            Json attributes = attributes();
            return attributes == null ? null : attributes.path(key);
        }

        public String attributeText(String key) {
            Json value = attribute(key);
            return value == null ? null : value.scalarText();
        }

        public Integer attributeInt(String key) {
            Json value = attribute(key);
            return value instanceof Json.JNumber n ? n.value().intValueExact() : null;
        }
    }

    /** One run: its directory, its {@code run.json} and its events. */
    public static final class Run {
        public final Path dir;
        public final Json runJson;
        public final List<Event> events;
        public final Json header;

        Run(Path dir) {
            this.dir = dir;
            this.runJson = NeutralJson.readDocument(dir.resolve("run.json"));
            List<Event> read = new ArrayList<>();
            for (NeutralJson.Line line : NeutralJson.readLines(dir.resolve("events.jsonl"))) {
                read.add(new Event(line.number(), line.json()));
            }
            this.events = List.copyOf(read);
            this.header = events.isEmpty() || !"header".equals(events.get(0).type()) ? null : events.get(0).json();
        }

        public String id() {
            return ClaudeStreamOracle.textOf(runJson, "id");
        }

        public String parentRunId() {
            Json parent = runJson.path("parentRunId");
            return parent == null || parent.isNull() ? null : parent.text();
        }

        public String spawnToolUseId() {
            Json config = runJson.path("config");
            return config == null ? null : ClaudeStreamOracle.textOf(config, ClaudeStreamContract.CONFIG_SPAWN_TOOL_USE_ID);
        }

        public String label() {
            return spawnToolUseId() == null ? "main" : "child:" + spawnToolUseId();
        }

        public List<Event> custom(String name) {
            List<Event> out = new ArrayList<>();
            for (Event e : events) {
                if ("custom".equals(e.type()) && name.equals(e.name())) {
                    out.add(e);
                }
            }
            return out;
        }

        public List<Event> ofType(String type) {
            List<Event> out = new ArrayList<>();
            for (Event e : events) {
                if (type.equals(e.type())) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    private final Run root;
    private final List<Run> children = new ArrayList<>();
    private final List<String> filesRead = new ArrayList<>();

    private CandidatePackage(Path rootRunDir) {
        this.root = new Run(rootRunDir);
        filesRead.add(rootRunDir.resolve("run.json").toString());
        filesRead.add(rootRunDir.resolve("events.jsonl").toString());
        // child runs are sibling run directories whose run.json names this run as the parent (any depth: a child's
        // child names the child); nothing else under the storage root is opened
        Path runs = rootRunDir.getParent();
        List<Path> siblings;
        try (Stream<Path> s = Files.list(runs)) {
            siblings = s.filter(Files::isDirectory).filter(p -> Files.isRegularFile(p.resolve("run.json"))).sorted().toList();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> linked = new ArrayList<>(List.of(root.id()));
        boolean progressed = true;
        List<Path> remaining = new ArrayList<>(siblings);
        remaining.remove(rootRunDir);
        while (progressed) {
            progressed = false;
            for (Path dir : new ArrayList<>(remaining)) {
                Json runJson = NeutralJson.readDocument(dir.resolve("run.json"));
                Json parent = runJson.path("parentRunId");
                String parentId = parent == null || parent.isNull() ? null : parent.text();
                if (parentId != null && linked.contains(parentId)) {
                    Run child = new Run(dir);
                    children.add(child);
                    linked.add(child.id());
                    filesRead.add(dir.resolve("run.json").toString());
                    filesRead.add(dir.resolve("events.jsonl").toString());
                    remaining.remove(dir);
                    progressed = true;
                }
            }
        }
    }

    /** Reads the root run at {@code rootRunDir} ({@code .../runs/<id>/}) and every run linked to it by {@code parentRunId}. */
    public static CandidatePackage read(Path rootRunDir) {
        return new CandidatePackage(rootRunDir);
    }

    /** Finds the one run under a storage root ({@code experiments/<exp>/runs/<id>/}) that has no parent. */
    public static Path findRootRun(Path storageRoot) {
        List<Path> roots = new ArrayList<>();
        try (Stream<Path> s = Files.walk(storageRoot)) {
            for (Path runJson : s.filter(p -> p.getFileName().toString().equals("run.json")).sorted().toList()) {
                Json run = NeutralJson.readDocument(runJson);
                Json parent = run.path("parentRunId");
                if (parent == null || parent.isNull()) {
                    roots.add(runJson.getParent());
                }
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (roots.size() != 1) {
            throw new IllegalArgumentException("expected exactly one root run under " + storageRoot + ", found " + roots);
        }
        return roots.get(0);
    }

    public Run root() {
        return root;
    }

    public List<Run> children() {
        return children;
    }

    public List<Run> runs() {
        List<Run> all = new ArrayList<>();
        all.add(root);
        all.addAll(children);
        return all;
    }

    public List<String> filesRead() {
        return filesRead;
    }
}
