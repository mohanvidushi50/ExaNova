package core;

import java.sql.*;



/** Retries short database transactions after a MySQL deadlock/lock timeout. */
public final class DeadlockRetry {
    @FunctionalInterface public interface Work { void run() throws SQLException; }
    private DeadlockRetry() {}

    public static void run(Work work) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try {
                work.run();
                return;
            } catch (SQLException e) {
                boolean retryable = e.getErrorCode() == 1213 || e.getErrorCode() == 1205
                        || "40001".equals(e.getSQLState());
                if (!retryable || attempt >= 3) throw e;
                try {
                    Thread.sleep(75L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Database retry interrupted.", interrupted);
                }
            }
        }
    }
}
