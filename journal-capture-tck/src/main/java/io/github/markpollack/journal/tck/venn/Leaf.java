package io.github.markpollack.journal.tck.venn;

/**
 * One scalar value found in a JSON record, with where it was found.
 *
 * @param kind the record's kind: its {@code type} in a vendor file, its {@code @type} in a journal file
 * @param path the path inside the record; object keys are joined with {@code .} and every array level is
 *        written {@code []}, so {@code message.content[].text}
 * @param value a {@link String}, a normalized {@link java.math.BigDecimal} or a {@link Boolean}; never
 *        {@code null} (a null carries no information and is not a leaf)
 * @param location the file and line the value came from, for the report
 * @param keptWhole whether the value lives inside a {@code vendor_event}, which keeps a vendor record whole
 */
public record Leaf(String kind, String path, Object value, String location, boolean keptWhole) {
}
