package io.github.markpollack.journal.storage;

import io.github.markpollack.journal.event.JournalEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The event types registered in this process, shared by every storage, so a registration is kept
 * when {@link io.github.markpollack.journal.Journal#configure} swaps the storage and reaches file
 * storages created later. {@link #generation()} changes on every new registration, so a file
 * storage knows to rebuild its reader: Jackson caches the reader for a base type, and a subtype
 * registered after the first load would otherwise be ignored.
 */
final class EventTypeRegistry {

    private static final Map<String, Class<? extends JournalEvent>> TYPES = new LinkedHashMap<>();

    private static int generation;

    private EventTypeRegistry() {
    }

    static synchronized void register(String typeName, Class<? extends JournalEvent> cls) {
        if (!cls.equals(TYPES.put(typeName, cls))) {
            generation++;
        }
    }

    static synchronized Map<String, Class<? extends JournalEvent>> types() {
        return Map.copyOf(TYPES);
    }

    static synchronized int generation() {
        return generation;
    }
}
