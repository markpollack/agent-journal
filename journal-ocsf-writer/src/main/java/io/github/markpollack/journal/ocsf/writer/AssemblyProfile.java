package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A reviewed source profile for final assembly of one CLI, version and route (resource
 * {@code ocsf/profiles/<id>.json}): which source roles exist and how each is acquired, framed, mapped and bound to the
 * execution; which declared identities relate observations of different sources (the joins); the order in which
 * sources take precedence for provenance members; and the profile's admission status. It is data the one
 * {@link Assembler} executes, never per-CLI merge code; the whole document is declared in the final file.
 *
 * @param document the profile document exactly as bundled
 */
public record AssemblyProfile(Jv document) {

    /** One declared source role. */
    public record Role(String role, String profile, String route, int kind, String requirement, String framing, String identity, Jv json) {

        boolean required() {
            return "required".equals(requirement);
        }
    }

    /**
     * One declared join: observations of {@code className} (at {@code activities}, any when empty) produced by
     * {@code rules} (any when empty) in sources of {@code roles} that carry equal values at every {@code key} pointer
     * observe the same entity at the same stage, in {@code namespace}.
     */
    public record Join(String id, String className, List<Integer> activities, List<String> key, String namespace, List<String> roles, List<String> rules, List<Integer> stageOrder,
            Jv json) {
    }

    /**
     * A declared authority for one field of one class: when observations conflict there, the value of {@code role} is
     * the canonical one, for the stated reason; the conflict stays listed.
     */
    public record Authority(String className, String pointer, String role, String basis) {
    }

    public static AssemblyProfile bundled(String id) {
        String resource = "/ocsf/profiles/" + id + ".json";
        try (InputStream in = AssemblyProfile.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no assembly profile " + id);
            }
            return new AssemblyProfile(Jv.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String id() {
        return document.member("profile_id").text();
    }

    public List<Role> roles() {
        List<Role> out = new ArrayList<>();
        for (Jv r : document.member("sources").elements()) {
            out.add(new Role(r.member("role").text(), r.member("profile").text(), r.member("route").text(), (int) ((Jv.Num) r.member("kind")).value().longValueExact(),
                    r.member("requirement").text(), r.member("framing") == null ? "lines" : r.member("framing").text(),
                    r.member("identity") == null ? "events" : r.member("identity").text(), r));
        }
        return out;
    }

    public Role role(String role) {
        for (Role r : roles()) {
            if (r.role().equals(role)) {
                return r;
            }
        }
        throw new IllegalArgumentException("profile " + id() + " declares no source role " + role);
    }

    public List<Join> joins() {
        List<Join> out = new ArrayList<>();
        for (Jv j : document.member("joins").elements()) {
            out.add(new Join(j.member("id").text(), j.member("class").text(), integers(j.member("activities")), strings(j.member("key")), j.member("namespace").text(),
                    strings(j.member("roles")), strings(j.member("rules")), integers(j.member("stage_order")), j));
        }
        return out;
    }

    public List<Authority> authorities() {
        List<Authority> out = new ArrayList<>();
        for (Jv a : document.member("authorities") == null ? List.<Jv>of() : document.member("authorities").elements()) {
            out.add(new Authority(a.member("class").text(), a.member("pointer").text(), a.member("role").text(), a.member("basis").text()));
        }
        return out;
    }

    /** Roles in precedence order: the first observation of a group in this order is its primary. */
    public List<String> precedence() {
        return strings(document.member("precedence"));
    }

    static List<String> strings(Jv array) {
        List<String> out = new ArrayList<>();
        for (Jv v : array == null ? List.<Jv>of() : array.elements()) {
            out.add(v.text());
        }
        return out;
    }

    private static List<Integer> integers(Jv array) {
        List<Integer> out = new ArrayList<>();
        for (Jv v : array == null ? List.<Jv>of() : array.elements()) {
            out.add((int) ((Jv.Num) v).value().longValueExact());
        }
        return out;
    }
}
