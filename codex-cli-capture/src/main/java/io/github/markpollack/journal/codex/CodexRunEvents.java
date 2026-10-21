package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.trace.JournalStep;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The events a Codex capture is recorded as, shared by the parent run and its sub-agent child runs:
 * the {@code prompt} event, the {@code llm_call} metadata keys, one {@link ToolCallEvent} per tool
 * call, the {@code vendor_event}s and the derived step costs. Pure builders and the logging of what
 * they build; no state.
 */
final class CodexRunEvents {

    private CodexRunEvents() {
    }

    /** Logs the {@code prompt} custom event, if the prompt was captured. */
    static void logPrompt(Run target, String phaseName, String promptText) {
        if (promptText != null && !promptText.isEmpty()) {
            target.logEvent(CustomEvent.of("prompt", phaseAttributes(phaseName, "text", promptText)));
        }
    }

    /**
     * The {@code llm_call} metadata keys shared by the parent and the child runs, in this order:
     * phase name, error flag, error message (if any), the unreported cost marker, session ID and
     * CLI version (each if known).
     */
    static Map<String, Object> llmCallMetadata(String phaseName, boolean isError, String errorMessage,
            String sessionId, String cliVersion) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("phaseName", phaseName);
        metadata.put("isError", isError);
        if (errorMessage != null) {
            metadata.put("errorMessage", errorMessage);
        }
        metadata.put("costAvailable", false);
        metadata.put("costSource", "unreported");
        if (sessionId != null) {
            metadata.put("sessionId", sessionId);
        }
        if (cliVersion != null) {
            metadata.put("cliVersion", cliVersion);
        }
        return metadata;
    }

    /**
     * Logs, in order, one {@link ToolCallEvent} per tool call, the vendor events, then one derived
     * {@link StepCostEvent} per step, all analyzed at the same instant.
     */
    static void logToolCallsAndSteps(Run target, String phaseName, List<CodexToolUseRecord> toolUses,
            List<CodexVendorEvent> vendorEvents, List<JournalStep> steps) {
        for (CodexToolUseRecord tool : toolUses) {
            target.logEvent(toolCallEvent(tool));
        }
        logVendorEvents(target, phaseName, vendorEvents);
        Instant analyzedAt = Instant.now();
        for (JournalStep step : steps) {
            target.logDerivedEvent(StepCostEvent.fromStep(step, analyzedAt));
        }
    }

    static ToolCallEvent toolCallEvent(CodexToolUseRecord tool) {
        return ToolCallEvent.builder()
                .id(tool.id())
                .toolName(tool.name())
                .kind(tool.kind())
                .input(tool.input())
                .output(tool.output())
                .durationMs(-1) // not measured
                .success(!tool.isError())
                .errorMessage(tool.errorMessage())
                .build();
    }

    /**
     * Logs each rollout record the parser did not consume as a {@code vendor_event} custom event
     * with the attributes {@code vendor}, {@code kind}, {@code envelopeType}, {@code ordinal} (if
     * Codex gave one), {@code vendorTimestamp} (as Codex wrote it) and {@code payload} (the whole
     * record). The event's own time is Codex's timestamp when it parses, else now.
     */
    static void logVendorEvents(Run target, String phaseName, List<CodexVendorEvent> vendorEvents) {
        for (CodexVendorEvent vendorEvent : vendorEvents) {
            Map<String, Object> attributes = phaseAttributes(phaseName, "vendor", "codex");
            attributes.put("kind", vendorEvent.kind());
            attributes.put("envelopeType", vendorEvent.envelopeType());
            if (vendorEvent.ordinal() >= 0) {
                attributes.put("ordinal", vendorEvent.ordinal());
            }
            Instant when = Instant.now();
            if (vendorEvent.timestamp() != null) {
                attributes.put("vendorTimestamp", vendorEvent.timestamp());
                try {
                    when = Instant.parse(vendorEvent.timestamp());
                }
                catch (java.time.format.DateTimeParseException ignored) {
                    // keep now; the text is in the attributes
                }
            }
            attributes.put("payload", vendorEvent.payload());
            target.logEvent(new CustomEvent(when, "vendor_event", attributes));
        }
    }

    // Not Map.of, which throws NullPointerException for a capture with no phase name; such a
    // capture is recorded without the phase, since attributes must not hold null values.
    static Map<String, Object> phaseAttributes(String phaseName, String key, Object value) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (phaseName != null) {
            attributes.put("phase", phaseName);
        }
        attributes.put(key, value);
        return attributes;
    }
}
