package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.time.Clock;

/**
 * Writer settings. The clock supplies Journal's own capture/transformation times (never source times). The fault
 * injector lets tests make a physical write fail at a chosen point; production code passes {@link FaultInjector#NONE}.
 *
 * @param clock Journal's clock
 * @param autoFlushEveryRecords flush after this many records (0: only on explicit flush, end and close)
 * @param faults the fault injector
 * @param producerVersion the producer version string written into {@code shared.producer}
 */
public record WriterOptions(Clock clock, int autoFlushEveryRecords, FaultInjector faults, String producerVersion) {

    /** Called before each physical write; throwing makes that write fail as the file system would. */
    public interface FaultInjector {
        FaultInjector NONE = (operation, detail) -> { };

        void before(String operation, String detail) throws IOException;
    }

    public static WriterOptions defaults() {
        return new WriterOptions(Clock.systemUTC(), 0, FaultInjector.NONE, "prototype/full-1-complete-sources");
    }

    public WriterOptions withClock(Clock c) {
        return new WriterOptions(c, autoFlushEveryRecords, faults, producerVersion);
    }

    public WriterOptions withAutoFlush(int everyRecords) {
        return new WriterOptions(clock, everyRecords, faults, producerVersion);
    }

    public WriterOptions withFaults(FaultInjector f) {
        return new WriterOptions(clock, autoFlushEveryRecords, f, producerVersion);
    }
}
