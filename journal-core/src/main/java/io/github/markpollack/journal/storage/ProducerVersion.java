package io.github.markpollack.journal.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The version of this library, as the build wrote it into {@code producer-version.properties}.
 * {@link JsonFileStorage} writes it on the header line of the files it creates.
 */
final class ProducerVersion {

    /** The name this library writes as the producer of a file. */
    static final String PRODUCER = "agent-journal";

    private static final String VALUE = read();

    private ProducerVersion() {
    }

    /**
     * Returns the library version, such as {@code 1.11.0}.
     *
     * @return the version, or {@code "unknown"} if the build did not record one
     */
    static String value() {
        return VALUE;
    }

    private static String read() {
        try (InputStream in = ProducerVersion.class.getResourceAsStream("producer-version.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version");
            // An unfiltered placeholder means the resource was copied without the build filling it in.
            return version == null || version.isBlank() || version.startsWith("${") ? "unknown" : version.trim();
        } catch (IOException e) {
            return "unknown";
        }
    }
}
