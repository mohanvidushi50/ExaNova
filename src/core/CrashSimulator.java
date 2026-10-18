package core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;



/** Admin-only demonstration: ends the local app process so startup recovery can be shown. */
public final class CrashSimulator {
    private static final AtomicBoolean scheduled = new AtomicBoolean();
    private CrashSimulator() {}

    public static boolean schedule() {
        if (!scheduled.compareAndSet(false, true)) return false;
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "exam-crash-demonstration");
            t.setDaemon(true);
            return t;
        }).schedule(() -> System.exit(73), 500, TimeUnit.MILLISECONDS);
        return true;
    }
}
