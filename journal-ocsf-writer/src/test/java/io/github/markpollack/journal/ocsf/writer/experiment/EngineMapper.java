package io.github.markpollack.journal.ocsf.writer;

import java.util.ArrayList;
import java.util.List;

/**
 * MAP-1 experiment (NON-PRODUCTION): a {@link SourceMapper} whose field conversion for the representative cases is
 * delegated to a transformation engine while the Java glue keeps everything an expression cannot own: parse status,
 * record routing (which records are a case; the rest go to the baseline mapper), the time-fallback decision, the
 * environment the expression reads, cross-record state (the custom_tool_calls seen, for case c), rule registration,
 * family inventory and owner routing to the core. Subclasses only convert values and evaluate.
 */
abstract class EngineMapper implements SourceMapper {

    enum Profile {
        CLAUDE_STREAM(ClaudeStreamMapper.CONTRACT, "claude-stream"), CODEX_ROLLOUT(CodexRolloutMapper.CONTRACT, "codex-rollout");

        final String contract;
        final String program;

        Profile(String contract, String program) {
            this.contract = contract;
            this.program = program;
        }
    }

    final Profile profile;
    private final SourceMapper delegate;
    /** Case c state, kept by Java exactly as the baseline keeps it; handed to the engine on every case-c record. */
    private final List<Jv.Obj> calls = new ArrayList<>();
    private Jv.Obj staticEnv;
    int engineRecords;
    long lastEvaluateNanos;

    EngineMapper(Profile profile) {
        this.profile = profile;
        this.delegate = profile == Profile.CLAUDE_STREAM ? new ClaudeStreamMapper() : new CodexRolloutMapper();
    }

    @Override
    public String contractName() {
        return profile.contract;
    }

    @Override
    public void map(NativeRecord r, SourceContext ctx) {
        if (!r.parsed()) {
            delegate.map(r, ctx);
            return;
        }
        Jv o = r.json();
        boolean codex = profile == Profile.CODEX_ROLLOUT;
        if (codex && ExperimentSupport.isCodexCustomToolCall(o)) {
            calls.add(ExperimentSupport.callSeen(o));
        }
        if (!(codex ? ExperimentSupport.isCodexCase(o) : ExperimentSupport.isClaudeCase(o))) {
            delegate.map(r, ctx);
            return;
        }
        if (staticEnv == null) {
            staticEnv = ExperimentSupport.staticEnv(ctx, profile.contract);
            prepare(staticEnv);
        }
        MapperSupport.Time t = ctx.timeFor(r, MapperSupport.text(o, "timestamp"));
        Jv.Obj env = ExperimentSupport.recordEnv(r, t, codex ? calls : null);
        Jv out = evaluate(r, env);
        engineRecords++;
        ExperimentSupport.emit(ctx, r, profile.contract, out);
    }

    /** Converts the static environment once into the engine's model. */
    abstract void prepare(Jv.Obj staticEnv);

    /** Converts the record and the per-record environment, evaluates the compiled program, converts the result back. */
    abstract Jv evaluate(NativeRecord r, Jv.Obj env);
}
