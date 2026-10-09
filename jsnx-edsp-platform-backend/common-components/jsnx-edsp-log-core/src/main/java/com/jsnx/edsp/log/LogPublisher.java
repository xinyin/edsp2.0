package com.jsnx.edsp.log;

public interface LogPublisher extends AutoCloseable {
    /** Must be non-blocking for the request thread. */
    boolean publish(LogEvent event);

    default long droppedCount() { return 0L; }

    @Override
    default void close() {}
}
