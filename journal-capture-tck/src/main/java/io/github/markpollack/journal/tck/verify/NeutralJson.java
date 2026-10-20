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
