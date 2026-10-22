package io.github.markpollack.journal.tck.verify;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses JSON text into {@link Json} with Jackson's tokenizer only (neutral infrastructure: no data binding, no
 * tree model, no production types). Numbers are taken from the token text, so their exact value and their spelling
 * both survive; strings are unescaped, since escape spelling is not part of the semantic contract. Duplicate object
 * keys are refused rather than silently collapsed: the neutral model cannot carry them faithfully, and the caller
 * reports the record as unresolved.
 */
public final class NeutralJson {

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private NeutralJson() {
    }

    /** One line of a JSON Lines file: its 1-based number, its exact text and its parsed value. */
    public record Line(int number, String text, Json json) {
    }

    /**
     * @throws IllegalArgumentException if the text is not one JSON value (trailing content, duplicate keys, syntax)
     */
    public static Json parse(String text) {
        try (JsonParser parser = FACTORY.createParser(text)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new IllegalArgumentException("empty JSON text");
            }
            Json value = read(parser, first);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("trailing content after the JSON value");
            }
            return value;
        }
        catch (IOException e) {
            throw new IllegalArgumentException("not JSON: " + e.getMessage(), e);
        }
    }

    /**
     * One step from the root to a value: an object member, named, with its occurrence among the members of that object
     * that share the name (1 = first), or an array element by index ({@code member} {@code null}).
     */
    public record Step(String member, int occurrence, int index) {
        public static Step member(String name, int occurrence) {
            return new Step(name, occurrence, -1);
        }
    }

    /**
     * An object member that occurs more than once: the exact steps to its containing object, its name and every value in
     * source order. Names are kept whole, so a member literally named {@code item.id} is never the {@code id} of {@code item}.
     */
    public record Repeated(List<Step> container, String member, List<Json> values) {
        /** The RFC 6901 JSON Pointer of the member (each name with {@code ~} as {@code ~0} and {@code /} as {@code ~1}); occurrences are not part of it. */
        public String pointer() {
            StringBuilder sb = new StringBuilder();
            for (Step st : container) {
                sb.append('/').append(st.member() == null ? String.valueOf(st.index()) : escape(st.member()));
            }
            return sb.append('/').append(escape(member)).toString();
        }

        private static String escape(String name) {
            return name.replace("~", "~0").replace("/", "~1");
        }
    }

    /** A value whose repeated members are reported rather than collapsed; the tree holds each member's first value. */
    public record WithRepeats(Json value, List<Repeated> repeated) {
    }

    /**
     * Parses text that strict parsing refuses only because an object repeats a member, reporting every repeat at its
     * exact location with all its values. For a source profile that declares one such form; {@link #parse} stays strict.
     *
     * @throws IllegalArgumentException if the text is not one JSON value apart from repeated members
     */
    public static WithRepeats parseReportingRepeats(String text) {
        JsonFactory lenient = JsonFactory.builder().disable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        try (JsonParser parser = lenient.createParser(text)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new IllegalArgumentException("empty JSON text");
            }
            List<Repeated> repeated = new ArrayList<>();
            Json value = readRepeats(parser, first, List.of(), repeated);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("trailing content after the JSON value");
            }
            return new WithRepeats(value, List.copyOf(repeated));
        }
        catch (IOException e) {
            throw new IllegalArgumentException("not JSON: " + e.getMessage(), e);
        }
    }

    /** Reads one value at {@code steps}; an object's members are grouped by name in its own scope, so repeats are per containing object. */
    private static Json readRepeats(JsonParser parser, JsonToken token, List<Step> steps, List<Repeated> repeated) throws IOException {
        if (token == JsonToken.START_OBJECT) {
            Map<String, List<Json>> byName = new LinkedHashMap<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String key = parser.currentName();
                List<Json> vs = byName.computeIfAbsent(key, k -> new ArrayList<>());
                List<Step> child = new ArrayList<>(steps);
                child.add(Step.member(key, vs.size() + 1));
                vs.add(readRepeats(parser, parser.nextToken(), List.copyOf(child), repeated));
            }
            Map<String, Json> members = new LinkedHashMap<>();
            for (Map.Entry<String, List<Json>> e : byName.entrySet()) {
                members.put(e.getKey(), e.getValue().get(0));
                if (e.getValue().size() > 1) {
                    repeated.add(new Repeated(steps, e.getKey(), List.copyOf(e.getValue())));
                }
            }
            return new Json.JObject(members);
        }
        if (token == JsonToken.START_ARRAY) {
            List<Json> elements = new ArrayList<>();
            JsonToken next;
            while ((next = parser.nextToken()) != JsonToken.END_ARRAY) {
                List<Step> child = new ArrayList<>(steps);
                child.add(new Step(null, 0, elements.size()));
                elements.add(readRepeats(parser, next, List.copyOf(child), repeated));
            }
            return new Json.JArray(elements);
        }
        return read(parser, token);
    }

    /** Reads a JSON Lines file; blank lines are skipped, and a line that is not JSON is an error, never skipped. */
    public static List<Line> readLines(Path file) {
        List<Line> lines = new ArrayList<>();
        try {
            int number = 0;
            for (String text : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                number++;
                if (text.isBlank()) {
                    continue;
                }
                try {
                    lines.add(new Line(number, text, parse(text)));
                }
                catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(file + ":" + number + " " + e.getMessage(), e);
                }
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return lines;
    }

    /** Reads a file holding one JSON document. */
    public static Json readDocument(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The SHA-256 of a file's bytes, lower-case hex, for naming the exact oracle in a report. */
    public static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Json read(JsonParser parser, JsonToken token) throws IOException {
        switch (token) {
            case START_OBJECT -> {
                Map<String, Json> members = new LinkedHashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String key = parser.currentName();
                    members.put(key, read(parser, parser.nextToken()));
                }
                return new Json.JObject(members);
            }
            case START_ARRAY -> {
                List<Json> elements = new ArrayList<>();
                JsonToken next;
                while ((next = parser.nextToken()) != JsonToken.END_ARRAY) {
                    elements.add(read(parser, next));
                }
                return new Json.JArray(elements);
            }
            case VALUE_STRING -> {
                return new Json.JString(parser.getText());
            }
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                // the token text is the source spelling; the value is exact decimal arithmetic on that text
                return new Json.JNumber(parser.getText());
            }
            case VALUE_TRUE -> {
                return new Json.JBool(true);
            }
            case VALUE_FALSE -> {
                return new Json.JBool(false);
            }
            case VALUE_NULL -> {
                return Json.JNull.INSTANCE;
            }
            default -> throw new IllegalArgumentException("unexpected token " + token);
        }
    }

    /** Serializes a value back to compact JSON text (numbers by their spelling), for writing mutated candidates. */
    public static String write(Json json) {
        StringBuilder out = new StringBuilder();
        write(json, out);
        return out.toString();
    }

    private static void write(Json json, StringBuilder out) {
        if (json instanceof Json.JObject o) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Json> member : o.members().entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                quote(member.getKey(), out);
                out.append(':');
                write(member.getValue(), out);
            }
            out.append('}');
        }
        else if (json instanceof Json.JArray a) {
            out.append('[');
            for (int i = 0; i < a.elements().size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(a.elements().get(i), out);
            }
            out.append(']');
        }
        else if (json instanceof Json.JString s) {
            quote(s.value(), out);
        }
        else if (json instanceof Json.JNumber n) {
            out.append(n.text());
        }
        else if (json instanceof Json.JBool b) {
            out.append(b.value());
        }
        else {
            out.append("null");
        }
    }

    private static void quote(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
