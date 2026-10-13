package com.evalio;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Gives each incoming web request its own lightweight Java 21 virtual thread. */
final class ExamConcurrency {
    private ExamConcurrency() { }

    static ExecutorService requestExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
