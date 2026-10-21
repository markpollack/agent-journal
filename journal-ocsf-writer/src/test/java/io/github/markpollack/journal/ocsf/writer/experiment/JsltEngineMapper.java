package io.github.markpollack.journal.ocsf.writer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schibsted.spt.data.jslt.Expression;
import com.schibsted.spt.data.jslt.Parser;

import java.io.StringReader;

/**
 * MAP-1 experiment (NON-PRODUCTION): JSLT 0.1.14 (com.schibsted.spt.data:jslt) as the field-conversion engine. The
 * program is compiled once per mapper instance; {@link Expression} is immutable after compilation and is applied with
 * a fresh scope per call (the library documents compiled expressions as safe to share between threads). Object
 * filter: {@code . != null} (keep empty containers and false/0/""; drop null-valued keys), replacing JSLT's default
 * filter, which also drops empty objects and arrays. Input records are parsed by Jackson databind
 * ({@code ObjectMapper.readTree}) exactly as a JSLT user would; the output tree is serialized by Jackson and re-read
 * into the writer's {@link Jv} tree.
 */
final class JsltEngineMapper extends EngineMapper {

    static final String FILTER = ". != null";

    private final ObjectMapper mapper;
    private final Expression program;
    private JsonNode staticC;

    JsltEngineMapper(Profile profile, boolean bigDecimalFloats) {
        this(profile, bigDecimalFloats, compile(profile.program + ".jslt", ExperimentSupport.resource(profile.program + ".jslt")));
    }

    /** Shares an already compiled program (compile once, outside the per-record loop; one mapper per source/thread). */
    JsltEngineMapper(Profile profile, boolean bigDecimalFloats, Expression program) {
        super(profile);
        this.mapper = bigDecimalFloats ? new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS) : new ObjectMapper();
        this.program = program;
    }

    static Expression compile(Profile profile) {
        return compile(profile.program + ".jslt", ExperimentSupport.resource(profile.program + ".jslt"));
    }

    static Expression compile(String name, String source) {
        return new Parser(new StringReader(source)).withSource(name).withObjectFilter(FILTER).compile();
    }

    @Override
    void prepare(Jv.Obj staticEnv) {
        staticC = ExperimentSupport.toJackson(staticEnv, mapper);
    }

    @Override
    Jv evaluate(NativeRecord r, Jv.Obj env) {
        try {
            JsonNode record = mapper.readTree(r.text());
            ObjectNode e = (ObjectNode) ExperimentSupport.toJackson(env, mapper);
            e.set("c", staticC);
            ObjectNode input = mapper.createObjectNode();
            input.set("record", record);
            input.set("env", e);
            long t0 = System.nanoTime();
            JsonNode out = program.apply(input);
            lastEvaluateNanos = System.nanoTime() - t0;
            return Jv.parse(mapper.writeValueAsString(out));
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
