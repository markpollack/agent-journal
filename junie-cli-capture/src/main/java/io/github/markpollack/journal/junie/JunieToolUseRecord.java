package io.github.markpollack.journal.junie;

import io.github.markpollack.journal.event.ToolKind;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One Junie step, folded from every {@code *BlockUpdatedEvent} sharing a {@code stepId}.
 *
 * <p>
 * <strong>Junie has no raw tool-name field.</strong> Where Claude emits {@code "Bash"} and
 * Antigravity emits {@code "run_command"}, Junie names nothing: the identity of an action is split
 * between the <em>event kind</em> that reports it and a payload field. So the portfolio rule "the
 * raw provider tool name stays in {@code name}" has no literal referent, and this adapter has to
 * choose one. It chooses the <strong>event kind, verbatim</strong> — see {@link #name()}.
 *
 * @param id           the Junie {@code stepId} (a UUID), stable across every update for the step
 * @param kind         canonical ACP-aligned category, derived additively from {@code name}
 * @param name         the verbatim Junie event kind that most specifically described this step —
 *                     {@code TerminalBlockUpdatedEvent}, {@code ViewFilesBlockUpdatedEvent},
 *                     {@code FileChangesBlockUpdatedEvent}, or {@code ToolBlockUpdatedEvent}.
 *                     <p>
 *                     <strong>Why the event kind and not the prose.</strong> The one human-readable
 *                     string Junie offers is {@code ToolBlockUpdatedEvent.text} — model-authored
 *                     English like {@code "Open calc.py"}. Keyed as a Markov state that is
 *                     unbounded-cardinality: every step becomes its own symbol, no transition ever
 *                     repeats, and the matrix is as useless as Codex's opposite failure of
 *                     collapsing every step to {@code exec}. The event kind is the only identifier
 *                     Junie emits that is machine-authored, closed, stable across runs, and
 *                     actually distinguishes actions — three distinct values over the seven tool
 *                     steps of the rich fixture. It is also literally raw: no derivation, no
 *                     invention, byte-for-byte the vendor's own string, greppable in the trace.
 *                     <p>
 *                     The prose is not discarded — it is preserved in {@link #input()} under
 *                     {@code description}, where a future classifier can reprocess it without
 *                     having polluted the state alphabet.
 * @param input        normalised payload: {@code command} for terminal steps, {@code files} for
 *                     view steps, {@code paths} for edit steps, {@code description} for the prose
 * @param output       terminal output, or the change summary, when the step reported one
 * @param status       last-observed Junie status ({@code IN_PROGRESS}, {@code COMPLETED},
 *                     {@code FAILED})
 * @param exitCode     process exit code for terminal steps, or -1 when not applicable/reported
 * @param isError      whether the folded terminal status was {@code FAILED}, or a non-zero exit
 * @param errorMessage provider error detail, when present
 */
public record JunieToolUseRecord(
        String id,
        ToolKind kind,
        String name,
        Map<String, Object> input,
        Object output,
        String status,
        int exitCode,
        boolean isError,
        String errorMessage
) {

    public JunieToolUseRecord {
        kind = kind != null ? kind : ToolKind.OTHER;
        input = input == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    /** ACP wire spelling of the canonical kind. */
    public String classification() {
        return kind.wireValue();
    }

    /** Whether a process exit code was actually observed for this step. */
    public boolean hasExitCode() {
        return exitCode >= 0;
    }
}
