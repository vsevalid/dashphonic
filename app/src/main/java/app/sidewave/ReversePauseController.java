package app.sidewave;

/** Playback ownership for reverse gear, independent of Android timers and firmware callbacks. */
final class ReversePauseController {
    enum Action { NONE, PAUSE, RESUME }

    static final long ENGAGE_MS = 1_000;
    static final long ACK_MS = 2_500;
    static final long EXIT_MS = 250;
    private boolean enabled;
    private Boolean reverse;
    private long enteredAt;
    private long pauseAt;
    private long exitedAt;
    private boolean pauseSent;
    private boolean pauseConfirmed;
    private boolean consumed;

    void configure(boolean on) {
        if (enabled == on) return;
        enabled = on;
        cancel();
    }

    void onReverse(boolean on, long now) {
        if (reverse != null && reverse == on) return;
        reverse = on;
        if (on) {
            enteredAt = now;
            if (!pauseSent) consumed = false;
        } else exitedAt = now;
    }

    void onPlayback(boolean playing, long now) {
        if (!pauseSent) return;
        if (now < pauseAt) return;
        if (!pauseConfirmed && now - pauseAt >= ACK_MS) { cancel(); return; }
        if (!playing) pauseConfirmed = true;
        else if (pauseConfirmed) cancel();
    }

    Action tick(long now, boolean eligible, boolean playing) {
        if (!enabled || reverse == null) return Action.NONE;
        if (!eligible) { cancel(); return Action.NONE; }
        // A missing acknowledgement must never become a delayed, unsolicited Play.
        if (pauseSent && !pauseConfirmed && now - pauseAt >= ACK_MS) cancel();
        if (reverse) {
            if (!consumed && !pauseSent && now - enteredAt >= ENGAGE_MS) {
                consumed = true;
                if (playing) {
                    pauseSent = true;
                    pauseAt = now;
                    return Action.PAUSE;
                }
            }
        } else if (pauseSent && pauseConfirmed && !playing && now - exitedAt >= EXIT_MS) {
            cancel();
            return Action.RESUME;
        }
        return Action.NONE;
    }

    long nextDelayMs(long now) {
        if (!enabled || reverse == null) return -1;
        if (pauseSent && !pauseConfirmed) return Math.max(1, ACK_MS - (now - pauseAt));
        if (!reverse && pauseSent && pauseConfirmed) return Math.max(0, EXIT_MS - (now - exitedAt));
        if (reverse && !consumed && !pauseSent) return Math.max(0, ENGAGE_MS - (now - enteredAt));
        return -1;
    }

    boolean blocksAutoPlay() { return enabled && Boolean.TRUE.equals(reverse); }
    Boolean reverse() { return reverse; }

    void cancel() {
        pauseSent = false;
        pauseConfirmed = false;
        consumed = true;
    }

    void reset() {
        cancel();
        reverse = null;
    }
}
