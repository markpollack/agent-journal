package io.github.markpollack.journal.ocsf.writer;

/**
 * A plain-text artifact such as a captured stderr (contract {@code opaque-text}): every line is retained whole, as a
 * parsed record when it happens to be one JSON object and as an opaque record otherwise. Nothing is interpreted.
 */
final class OpaqueTextMapper implements SourceMapper {

    static final String CONTRACT = "opaque-text";

    @Override
    public String contractName() {
        return CONTRACT;
    }

    @Override
    public void map(NativeRecord r, SourceContext ctx) {
        if (r.parsed()) {
            ctx.family("json", r.raw().length, "retained-only: a text line that is one JSON object; not interpreted");
            ctx.emit(ctx.fallback(r, CONTRACT, "text.parsed->source_record", "text", null, null, ctx.run.sessionUid(), "a text artifact line that parses as JSON; retained whole, not interpreted"));
        }
        else {
            ctx.family("text", r.raw().length, "retained-only: text line (bytes kept verbatim)");
            ctx.emit(ctx.opaque(r, CONTRACT));
        }
    }
}
