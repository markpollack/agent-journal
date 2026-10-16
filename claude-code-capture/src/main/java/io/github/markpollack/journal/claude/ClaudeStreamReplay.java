package io.github.markpollack.journal.claude;

import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds the SDK's messages from a raw stream file, so the normalized record can be derived
 * again from level 0 alone: after a parser fix, or after a run was killed and only the stream file
 * survived. Pass {@link Replay#messages()} to {@link SessionLogParser#parse}.
 *
 * <p>Each line is parsed as the live run parsed it. A line that cannot be turned into a message (a
 * message type the SDK does not know, malformed JSON, or the partial last line of a run that was
 * cut off) is not dropped silently: it is returned in {@link Replay#skipped()} with its line
 * number and exact text. The replayed capture has no {@code source} references to stream lines,
 * because the skipped lines would shift their numbers; trace lines written during the live run
 * carry them.
 */
public final class ClaudeStreamReplay {

    private ClaudeStreamReplay() {
    }

    /**
     * A line that did not become a message.
     *
     * @param seq the line's number in the stream file, starting at 1
     * @param reason why it was skipped
     * @param line the line exactly as stored
     */
    public record Skipped(long seq, String reason, String line) {
    }

    /**
     * The result of replaying a stream file.
     *
     * @param messages the messages, in stream order, each with its original line
     * @param skipped the lines that did not become messages
     * @param lines the number of lines in the file
     */
    public record Replay(List<ParsedMessage> messages, List<Skipped> skipped, long lines) {
    }

    /**
     * Reads a raw stream file written by {@link io.github.markpollack.journal.raw.RawStream}.
     *
     * @param streamFile the stream file
     * @return the messages and the skipped lines
     * @throws IOException if the file cannot be read
     */
    public static Replay read(Path streamFile) throws IOException {
        MessageParser parser = new MessageParser();
        List<ParsedMessage> messages = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        long seq = 0;
        for (String line : Files.readAllLines(streamFile)) {
            seq++;
            if (line.isBlank()) {
                skipped.add(new Skipped(seq, "blank line", line));
                continue;
            }
            try {
                messages.add(ParsedMessage.RegularMessage.of(parser.parseMessage(line), line));
            }
            catch (RuntimeException ex) {
                skipped.add(new Skipped(seq, ex.getClass().getSimpleName() + ": " + ex.getMessage(), line));
            }
        }
        return new Replay(List.copyOf(messages), List.copyOf(skipped), seq);
    }

}
