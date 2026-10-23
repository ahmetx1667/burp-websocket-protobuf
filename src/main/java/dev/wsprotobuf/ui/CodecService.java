package dev.wsprotobuf.ui;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded work queue shared by editor instances; closed when Burp unloads. */
public final class CodecService implements AutoCloseable {
    private final ThreadPoolExecutor executor;

    public CodecService() {
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(2, 2, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "websocket-protobuf-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    public Future<?> submit(Runnable action) {
        executor.purge();
        return executor.submit(action);
    }

    public boolean isClosed() { return executor.isShutdown(); }

    @Override public void close() { executor.shutdownNow(); }
}
