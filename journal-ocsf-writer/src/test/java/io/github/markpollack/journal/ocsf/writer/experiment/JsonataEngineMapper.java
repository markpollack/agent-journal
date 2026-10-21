package io.github.markpollack.journal.ocsf.writer;

import com.dashjoin.jsonata.Functions;
import com.dashjoin.jsonata.Jsonata;
import com.dashjoin.jsonata.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MAP-1 experiment (NON-PRODUCTION): Dashjoin jsonata-java 0.9.8 (com.dashjoin:jsonata) as the field-conversion
 * engine. The expression is parsed once per mapper instance ({@code Jsonata.jsonata(text)}). Thread model observed
 * in the 0.9.8 source: {@code evaluate} runs on a per-thread copy of the parsed expression obtained through a
 * static {@code ThreadLocal<Jsonata>} (the copy shares the AST and environment); the public contract is "evaluate
 * is safe from multiple threads after parsing". Input records are parsed by the library's own JSON reader
 * ({@code Json.parseJson}), which reads every number through {@code Double.valueOf}; the output tree is serialized by
 * the library's {@code $string} implementation and re-read into the writer's {@link Jv} tree.
 */
final class JsonataEngineMapper extends EngineMapper {

    private final Jsonata program;
    private Object staticC;

    JsonataEngineMapper(Profile profile) {
        this(profile, compile(profile));
    }

    /** Shares an already parsed expression (parse once, outside the per-record loop; one mapper per source/thread). */
    JsonataEngineMapper(Profile profile, Jsonata program) {
        super(profile);
        this.program = program;
    }

    static Jsonata compile(Profile profile) {
        return Jsonata.jsonata(ExperimentSupport.resource(profile.program + ".jsonata"));
    }

    @Override
    void prepare(Jv.Obj staticEnv) {
        staticC = ExperimentSupport.toJava(staticEnv);
    }

    @Override
    @SuppressWarnings("unchecked")
    Jv evaluate(NativeRecord r, Jv.Obj env) {
        Object record = Json.parseJson(r.text());
        Map<String, Object> e = (Map<String, Object>) ExperimentSupport.toJava(env);
        e.put("c", staticC);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("record", record);
        input.put("env", e);
        long t0 = System.nanoTime();
        Object out = program.evaluate(input);
        lastEvaluateNanos = System.nanoTime() - t0;
        if (out == null) {
            throw new IllegalStateException("JSONata program returned undefined for case record " + r.index());
        }
        return Jv.parse(Functions.string(out, false));
    }
}
