package core;

import core.Application.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;



/** Runs background database work in first-come, first-served order. */
public final class BackgroundJobs {
    private static final ThreadPoolExecutor MAINTENANCE_FCFS = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), task -> {
                Thread thread = new Thread(task, "evalio-maintenance-worker");
                thread.setDaemon(true);
                return thread;
            });
    private static final ThreadPoolExecutor[] SAVE_QUEUES = createSaveQueues();
    private static final ScheduledExecutorService TIMER = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "evalio-checkpoint-timer");
                thread.setDaemon(true);
                return thread;
            });

    private BackgroundJobs() { }

    private static ThreadPoolExecutor[] createSaveQueues() {
        int count = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        ThreadPoolExecutor[] queues = new ThreadPoolExecutor[count];
        for (int i = 0; i < count; i++) {
            final int workerId = i;
            queues[i] = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), task -> {
                        Thread thread = new Thread(task, "evalio-fcfs-save-" + workerId);
                        thread.setDaemon(true);
                        return thread;
                    });
        }
        return queues;
    }

    private static ThreadPoolExecutor queueForExam(int exam) {
        return SAVE_QUEUES[Math.floorMod(exam, SAVE_QUEUES.length)];
    }

    public static void startBackgroundTasks(Store store) {
        TIMER.scheduleWithFixedDelay(() -> MAINTENANCE_FCFS.execute(() -> {
            try { store.expireExamTimers(); }
            catch (SQLException e) { System.err.println("Exam timer cycle failed: " + e.getMessage()); }
        }), 2, 5, TimeUnit.SECONDS);
        TIMER.scheduleWithFixedDelay(() -> MAINTENANCE_FCFS.execute(() -> {
            try { store.checkpointRunningExams(); }
            catch (SQLException e) { System.err.println("Checkpoint cycle failed: " + e.getMessage()); }
        }), 30, 30, TimeUnit.SECONDS);
    }

    public static void saveAnswers(Store store, User student, int exam, Map<String, String> answers)
            throws SQLException {
        try {
            queueForExam(exam).submit(() -> {
                store.saveAnswers(student, exam, answers);
                return null;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Autosave was interrupted.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sql) throw sql;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new SQLException("Autosave failed.", cause);
        }
    }
}
