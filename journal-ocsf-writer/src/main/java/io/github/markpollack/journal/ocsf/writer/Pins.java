package io.github.markpollack.journal.ocsf.writer;

/**
 * The pinned, checksummed toolchain this writer's packages name in {@code run.json}. These must equal
 * {@code prototype/ocsf-acp/toolchain.lock.json}; a test checks that. The extension uid is PROVISIONAL and
 * UNREGISTERED; every package written here is non-production.
 */
public final class Pins {

    public static final String PACKAGE_KIND = "agent-journal/ocsf-prototype-package";
    public static final String PACKAGE_VERSION = "0.3.0-prototype";
    public static final String READER_CONTRACT = "agent-journal/ocsf-reader";
    public static final String READER_CONTRACT_VERSION = "0.3";
    public static final String SEMANTIC_OPERATIONS_VERSION = "0.2";
    public static final String SEMANTIC_CONTRACT_RESOURCE = "/ocsf/semantic-contract-0.2.0-prototype.json";

    public static final String OCSF_SCHEMA_COMMIT = "856d462bd20dc46cc1ffed2dfffe3b91ef0fbeba";
    public static final String OCSF_COMPILER_COMMIT = "865887a9ee88580c471062f93da115e7599fa31d";
    public static final String TOOLKIT_RELEASE = "v0.9.0";
    public static final String TOOLKIT_COMMIT = "a99619fcd148791a6a9fe5f82c1e0d839f658591";
    public static final String TOOLKIT_SHA256 = "641fec9f8c457896c06ead9136fe8f4e50753cd95f8a68c24b25c2bcc9cfd5a5";
    public static final String EXTENSION_UID_STATUS = "PROVISIONAL, UNREGISTERED prototype assignment";

    public static final String WRITER_NAME = "journal-ocsf-writer OcsfRunWriter";

    private Pins() {
    }

    static Jv ocsfPins() {
        return new Jv.Obj()
                .put("release", TOOLKIT_RELEASE).put("commit", TOOLKIT_COMMIT).put("linux_amd64_sha256", TOOLKIT_SHA256);
    }
}
