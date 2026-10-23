package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The semantic/query declarations a package carries under {@code semantic_contract}: each names a
 * {@link SemanticOperation}, the schema paths it reads and the per-contract parameters (classifiers, update
 * semantics). {@link #validate} resolves every schema-path target against the embedded definition closure and
 * every operation name against the implemented set, so a declaration that points at a non-existent attribute or an
 * unimplemented operation is a defect of the package, not a silent no-op.
 */
public final class SemanticContract {

    /** The declarations this prototype embeds in every package (one source of truth, also used by the tests). */
    public static final String RESOURCE = "/ocsf/semantic-contract-0.5.0-prototype.json";

    private static final Pattern PATH = Pattern.compile("^(\\*|[a-z_]+)\\.[a-z_]+(\\[\\])?(\\.[a-z_]+(\\[\\])?)*$");

    private SemanticContract() {
    }

    public static Json loadDefault() {
        try (InputStream in = SemanticContract.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            return NeutralJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The declaration with {@code id}, or {@code null}. */
    public static Json declaration(Json contract, String operation) {
        for (Json d : contract == null ? List.<Json>of() : contract.member("declarations").elements()) {
            if (operation.equals(SchemaCatalog.text(d, "operation"))) {
                return d;
            }
        }
        return null;
    }

    /** Problems found; empty when every target resolves and every operation is implemented. */
    public static List<String> validate(Json contract, SchemaCatalog catalog) {
        return validate(contract, catalog, java.util.Set.of());
    }

    /**
     * @param absentClasses pinned class names the package declares no definition for because no event of that class
     *        was written (a standalone file declares classes before first use); a target under such a class is not a
     *        defect, the view is simply empty there
     */
    public static List<String> validate(Json contract, SchemaCatalog catalog, java.util.Collection<String> absentClasses) {
        List<String> problems = new ArrayList<>();
        List<String> raw = new ArrayList<>();
        Json decls = contract.member("declarations");
        if (!(decls instanceof Json.JArray)) {
            problems.add("semantic_contract.declarations is not an array");
            return problems;
        }
        for (Json d : decls.elements()) {
            String id = SchemaCatalog.text(d, "id");
            String op = SchemaCatalog.text(d, "operation");
            if (op == null || !SemanticOperation.known(op)) {
                problems.add(id + ": operation " + op + " is not implemented by this reader (" + SemanticOperation.VERSION + ")");
            }
            walk(d.member("targets"), id + ".targets", catalog, raw);
            for (Json c : d.member("classifiers") == null ? List.<Json>of() : d.member("classifiers").elements()) {
                walk(c.member("target"), id + ".classifiers.target", catalog, raw);
            }
        }
        for (String problem : raw) {
            if (!undeclaredTarget(problem, absentClasses)) {
                problems.add(problem);
            }
        }
        return problems;
    }

    /**
     * Whether a problem concerns a target under a pinned class the file declares no definition for, or a {@code *}
     * path that no declared class carries while some pinned class is undeclared: the view is empty there, not wrong.
     */
    public static boolean undeclaredTarget(String problem, java.util.Collection<String> absentClasses) {
        for (String cls : absentClasses) {
            if (problem.contains("unknown class " + cls)) {
                return true;
            }
        }
        return !absentClasses.isEmpty() && problem.contains(" in *.");
    }

    private static void walk(Json v, String where, SchemaCatalog catalog, List<String> problems) {
        if (v == null) {
            return;
        }
        if (v instanceof Json.JString s) {
            String text = s.value();
            if (PATH.matcher(text).matches()) {
                try {
                    catalog.resolve(text);
                }
                catch (IllegalArgumentException e) {
                    problems.add(where + ": " + e.getMessage());
                }
            }
            else if (text.matches("^[a-z_]+$") && where.endsWith(".events") && catalog.classNamed(text) == null) {
                problems.add(where + ": unknown class " + text);
            }
        }
        else if (v instanceof Json.JArray a) {
            for (Json el : a.elements()) {
                walk(el, where, catalog, problems);
            }
        }
        else if (v instanceof Json.JObject o) {
            for (Map.Entry<String, Json> m : o.members().entrySet()) {
                walk(m.getValue(), where + "." + m.getKey(), catalog, problems);
            }
        }
    }
}
