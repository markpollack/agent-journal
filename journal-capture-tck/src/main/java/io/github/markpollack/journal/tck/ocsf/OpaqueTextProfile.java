package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

/** A plain-text artifact (for example {@code stream.err}): every line is retained whole, parsed or opaque; no facts. */
final class OpaqueTextProfile implements SourceProfile {

    @Override
    public ExpectedFacts expected(NativeArtifact artifact, Json source, Json scope) {
        ExpectedFacts f = new ExpectedFacts(SourceProfile.text(source, "artifact"));
        for (NativeArtifact.Record r : artifact.records()) {
            f.owned.add(r.index());
        }
        return f;
    }
}
