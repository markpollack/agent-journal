package io.github.markpollack.journal.tck.requirements;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The requirement documents as classpath test resources, materialized to files for Agent Judge's {@link Path}-based
 * readers, plus the parser-independent view of what they intend: every {@code ###} heading in document order.
 *
 * <p>The intended roster is deliberately read with a looser rule than the Agent Judge readers use. The readers accept
 * {@code ### RULE-n} followed by a bold keyword line and a {@code **Reason:**} line, or {@code ### UCn-ACm: Title}
 * followed by one sentence; the permissive {@code from} skips anything else, the strict {@code fromStrict} (Agent
 * Judge 0.19.0) refuses the document. Comparing the parsed roster against every heading is the independent check that
 * turns a silently skipped requirement into a test failure whether or not the strict reader noticed it.
 */
final class RequirementDocuments {

    static final String RULES = "/requirements/agent-journal/rules.md";
    static final String CRITERIA = "/requirements/agent-journal/criteria.md";
    static final String NEGATIVE_RULES = "/requirements/negative-control/rules.md";
    static final String NEGATIVE_CRITERIA = "/requirements/negative-control/criteria.md";
    static final String APPLICABILITY_RULES = "/requirements/applicability-control/rules.md";
    static final String APPLICABILITY_CRITERIA = "/requirements/applicability-control/criteria.md";

    /** Any third-level heading; the identifier is the first token, with a trailing colon removed. */
    private static final Pattern ANY_HEADING = Pattern.compile("^###\\s+(\\S+?):?(?:\\s.*)?$");

    private RequirementDocuments() {
    }

    /** A document copied out of the classpath, with its content digest as the immutable revision. */
    record Document(String resource, Path file, byte[] bytes, String revision) {

        String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        /** Every {@code ###} heading identifier, in document order, duplicates included. */
        List<String> intendedIds() {
            List<String> ids = new ArrayList<>();
            for (String line : text().lines().toList()) {
                Matcher m = ANY_HEADING.matcher(line.strip());
                if (m.matches()) {
                    ids.add(m.group(1));
                }
            }
            return List.copyOf(ids);
        }
    }

    static Document load(String resource, Path work) {
        try (InputStream in = RequirementDocuments.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + resource);
            }
            byte[] bytes = in.readAllBytes();
            Files.createDirectories(work);
            Path file = work.resolve(resource.substring(resource.lastIndexOf('/') + 1));
            Files.write(file, bytes);
            return new Document(resource, file, bytes, "sha256:" + sha256(bytes));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The roster comparison the tests make: what the document intends against what the reader returned. Empty when
     * they agree exactly (same identifiers, same order, each once).
     */
    static List<String> rosterProblems(List<String> intended, List<String> parsed) {
        List<String> problems = new ArrayList<>();
        Set<String> intendedSet = new LinkedHashSet<>(intended);
        if (intendedSet.size() != intended.size()) {
            problems.add("duplicate heading identifiers: " + duplicates(intended));
        }
        Set<String> parsedSet = new LinkedHashSet<>(parsed);
        if (parsedSet.size() != parsed.size()) {
            problems.add("reader returned an identifier more than once: " + duplicates(parsed));
        }
        for (String id : intendedSet) {
            if (!parsedSet.contains(id)) {
                problems.add("heading " + id + " was not returned by the reader (malformed or skipped)");
            }
        }
        for (String id : parsedSet) {
            if (!intendedSet.contains(id)) {
                problems.add("reader returned " + id + " which is not a heading in the document");
            }
        }
        if (problems.isEmpty() && !intended.equals(parsed)) {
            problems.add("reader order differs from document order");
        }
        return List.copyOf(problems);
    }

    private static List<String> duplicates(List<String> ids) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (!seen.add(id) && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }
}
