package dev.wsprotobuf.ui;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** Small Swing boundary; codec work must never run through this helper. */
public final class Edt {
    private Edt() { }

    public static void run(Runnable action) {
        call(() -> { action.run(); return null; });
    }

    public static <T> T call(Callable<T> action) {
        if (SwingUtilities.isEventDispatchThread()) return invoke(action);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { result.set(invoke(action)); }
                catch (RuntimeException error) { failure.set(error); }
            });
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while updating the editor", error);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("Could not update the editor", error.getCause());
        }
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private static <T> T invoke(Callable<T> action) {
        try { return action.call(); }
        catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
