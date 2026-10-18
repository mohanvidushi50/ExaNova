package core;



/** Shared rules for teacher configured exam durations and persisted deadlines. */
public final class ExamTimer {
    public static final int DEFAULT_MINUTES = 60;
    static final int MIN_MINUTES = 5;
    static final int MAX_MINUTES = 240;

    private ExamTimer() { }

    public static int validateMinutes(String value) {
        final int minutes;
        try { minutes = Integer.parseInt(value); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Enter the exam time in minutes."); }
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            throw new IllegalArgumentException("Exam time must be between 5 and 240 minutes.");
        }
        return minutes;
    }
}
