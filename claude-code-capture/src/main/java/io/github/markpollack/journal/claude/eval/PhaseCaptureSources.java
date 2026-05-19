package io.github.markpollack.journal.claude.eval;

import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.ToolResultRecord;
import io.github.markpollack.journal.claude.ToolUseRecord;
import io.github.markpollack.journal.eval.EvalSubject;
import io.github.markpollack.journal.eval.EvalSubjectKind;
import io.github.markpollack.journal.eval.EvalSubjectSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory for creating {@link EvalSubjectSource} instances backed by
 * {@link PhaseCapture} records from the Claude Code SDK bridge.
 *
 * <p>Each phase becomes an {@link EvalSubjectKind#LLM_CALL LLM_CALL} subject.
 * Individual tool uses within a phase become separate
 * {@link EvalSubjectKind#TOOL_CALL TOOL_CALL} subjects.
 */
public final class PhaseCaptureSources {

	private PhaseCaptureSources() {
	}

	/**
	 * Extract subjects from PhaseCapture records.
	 * @param phases the phase captures to convert
	 * @return a source producing EvalSubjects from the phases
	 */
	public static EvalSubjectSource fromPhaseCaptures(List<PhaseCapture> phases) {
		List<EvalSubject> subjects = mapPhases(phases);
		return subjects::stream;
	}

	private static List<EvalSubject> mapPhases(List<PhaseCapture> phases) {
		List<EvalSubject> subjects = new ArrayList<>();
		for (int i = 0; i < phases.size(); i++) {
			PhaseCapture phase = phases.get(i);
			String phaseId = "phase:" + phase.sessionId() + ":" + i;

			// Phase-level subject (LLM_CALL)
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("phaseName", phase.phaseName());
			metadata.put("sessionId", phase.sessionId());
			metadata.put("inputTokens", phase.inputTokens());
			metadata.put("outputTokens", phase.outputTokens());
			metadata.put("thinkingTokens", phase.thinkingTokens());
			metadata.put("totalTokens", phase.totalTokens());
			metadata.put("costUsd", phase.totalCostUsd());
			metadata.put("durationMs", phase.durationMs());
			metadata.put("numTurns", phase.numTurns());
			metadata.put("success", !phase.isError());

			subjects.add(new EvalSubject(
					phaseId, EvalSubjectKind.LLM_CALL, "phase_capture", null, null,
					"Phase: " + phase.phaseName(),
					phase.promptText(), phase.textOutput(),
					Map.copyOf(metadata)));

			// Tool-level subjects (TOOL_CALL) for each tool use in the phase
			if (phase.hasToolUses()) {
				Map<String, String> toolResults = buildToolResultMap(phase);
				List<ToolUseRecord> toolUses = phase.toolUses();
				for (int j = 0; j < toolUses.size(); j++) {
					ToolUseRecord tool = toolUses.get(j);
					String toolId = phaseId + ":tool:" + j;

					Map<String, Object> toolMeta = new LinkedHashMap<>();
					toolMeta.put("toolName", tool.name());
					toolMeta.put("phaseName", phase.phaseName());
					toolMeta.put("sessionId", phase.sessionId());
					toolMeta.put("toolUseId", tool.id());

					String toolOutput = toolResults.get(tool.id());
					boolean isError = isToolError(phase, tool.id());
					toolMeta.put("success", !isError);

					subjects.add(new EvalSubject(
							toolId, EvalSubjectKind.TOOL_CALL, "phase_capture", null, null,
							"Tool call: " + tool.name(),
							tool.input(), toolOutput,
							Map.copyOf(toolMeta)));
				}
			}
		}
		return subjects;
	}

	private static Map<String, String> buildToolResultMap(PhaseCapture phase) {
		Map<String, String> results = new LinkedHashMap<>();
		if (phase.hasToolResults()) {
			for (ToolResultRecord result : phase.toolResults()) {
				results.put(result.toolUseId(), result.content());
			}
		}
		return results;
	}

	private static boolean isToolError(PhaseCapture phase, String toolUseId) {
		if (!phase.hasToolResults()) {
			return false;
		}
		return phase.toolResults().stream()
				.filter(r -> r.toolUseId().equals(toolUseId))
				.findFirst()
				.map(ToolResultRecord::isError)
				.orElse(false);
	}

}
