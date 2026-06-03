package io.github.markpollack.journal.claude;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes one JSON line per event to a JSONL trace file. Each line is flushed
 * immediately so {@code tail -f} works during long-running agent sessions.
 *
 * <p>
 * No Jackson dependency — uses simple {@code String.format()} for JSON
 * construction.
 */
class TraceWriter implements Closeable {

	private final BufferedWriter writer;

	private final AtomicInteger seq = new AtomicInteger(0);

	TraceWriter(Path traceFile) throws IOException {
		Files.createDirectories(traceFile.getParent());
		this.writer = Files.newBufferedWriter(traceFile);
	}

	void writeToolUse(String name, String id, Map<String, Object> input) throws IOException {
		String inputJson = toJson(input);
		String line = String.format(
				"{\"ts\":\"%s\",\"seq\":%d,\"type\":\"tool_use\",\"name\":\"%s\",\"id\":\"%s\",\"input\":%s}",
				Instant.now().toString(), seq.getAndIncrement(), escapeJson(name), escapeJson(id), inputJson);
		writeLine(line);
	}

	void writeToolResult(String id, boolean isError, int contentLength) throws IOException {
		String line = String.format(
				"{\"ts\":\"%s\",\"seq\":%d,\"type\":\"tool_result\",\"id\":\"%s\",\"isError\":%s,\"contentLength\":%d}",
				Instant.now().toString(), seq.getAndIncrement(), escapeJson(id), isError, contentLength);
		writeLine(line);
	}

	void writeText(int length) throws IOException {
		String line = String.format("{\"ts\":\"%s\",\"seq\":%d,\"type\":\"text\",\"length\":%d}",
				Instant.now().toString(), seq.getAndIncrement(), length);
		writeLine(line);
	}

	void writeThinking(int length) throws IOException {
		String line = String.format("{\"ts\":\"%s\",\"seq\":%d,\"type\":\"thinking\",\"length\":%d}",
				Instant.now().toString(), seq.getAndIncrement(), length);
		writeLine(line);
	}

	void writeResult(int inputTokens, int outputTokens, double costUsd, int numTurns, long durationMs)
			throws IOException {
		String line = String.format(
				"{\"ts\":\"%s\",\"seq\":%d,\"type\":\"result\",\"inputTokens\":%d,\"outputTokens\":%d,"
						+ "\"costUsd\":%.6f,\"numTurns\":%d,\"durationMs\":%d}",
				Instant.now().toString(), seq.getAndIncrement(), inputTokens, outputTokens, costUsd, numTurns,
				durationMs);
		writeLine(line);
	}

	private void writeLine(String line) throws IOException {
		writer.write(line);
		writer.newLine();
		writer.flush();
	}

	@Override
	public void close() throws IOException {
		writer.close();
	}

	private static String escapeJson(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}

	@SuppressWarnings("unchecked")
	static String toJson(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof String s) {
			return "\"" + escapeJson(s) + "\"";
		}
		if (value instanceof Number || value instanceof Boolean) {
			return value.toString();
		}
		if (value instanceof Map<?, ?> map) {
			StringBuilder sb = new StringBuilder("{");
			boolean first = true;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (!first) {
					sb.append(",");
				}
				sb.append("\"").append(escapeJson(String.valueOf(entry.getKey()))).append("\":");
				sb.append(toJson(entry.getValue()));
				first = false;
			}
			sb.append("}");
			return sb.toString();
		}
		if (value instanceof List<?> list) {
			StringBuilder sb = new StringBuilder("[");
			for (int i = 0; i < list.size(); i++) {
				if (i > 0) {
					sb.append(",");
				}
				sb.append(toJson(list.get(i)));
			}
			sb.append("]");
			return sb.toString();
		}
		return "\"" + escapeJson(value.toString()) + "\"";
	}

}
