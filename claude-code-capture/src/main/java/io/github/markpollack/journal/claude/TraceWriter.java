/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal.claude;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

	void writeToolUse(String name, String id) throws IOException {
		String line = String.format("{\"ts\":\"%s\",\"seq\":%d,\"type\":\"tool_use\",\"name\":\"%s\",\"id\":\"%s\"}",
				Instant.now().toString(), seq.getAndIncrement(), escapeJson(name), escapeJson(id));
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
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

}
