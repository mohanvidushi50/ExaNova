 package core;

import java.util.concurrent.*;



/** Gives each incoming web request its own lightweight Java 21 virtual thread. */
final class ExamConcurrency {
    private ExamConcurrency() { }

    static ExecutorService requestExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
