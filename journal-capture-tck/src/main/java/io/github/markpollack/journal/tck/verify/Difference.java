package io.github.markpollack.journal.tck.verify;

/**
 * One difference between an expected JSON value and an actual one.
 *
 * @param path where, from the compared root ({@code $.input.limit}, {@code $.content[1].text})
 * @param kind what kind of difference
 * @param detail a short description that names kinds and number spellings, never string content
 */
public record Difference(String path, Kind kind, String detail) {

    public enum Kind {
        /** Same kind of value, different value (a different string, a different exact number, a different boolean). */
        VALUE,
        /** A number equal in value but spelled differently ({@code 1} and {@code 1.0}, {@code 2.50} and {@code 2.5}). */
        REPRESENTATION,
        /** A different kind of value (a boolean for a number, a string for an array). */
        KIND,
        /** A {@code null} where a value was expected, or a value where {@code null} was expected. */
        NULL_VS_VALUE,
        /** An expected member or element is absent. */
        MISSING,
        /** A member or element the expected value does not have. */
        EXTRA
    }

    @Override
    public String toString() {
        return kind + " at " + path + ": " + detail;
    }
}
