package app.sidewave;

import static app.sidewave.ReversePauseController.Action.*;

public final class ReversePauseControllerTest {
    private static int checks;

    private static void expect(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual + " at check " + checks);
    }

    private static ReversePauseController fresh() {
        var c = new ReversePauseController();
        c.configure(true);
        c.onReverse(false, 0);
        c.onReverse(true, 10);
        return c;
    }

    private static ReversePauseController paused() {
        var c = fresh();
        expect(PAUSE, c.tick(1010, true, true));
        c.onPlayback(false, 1100);
        return c;
    }

    public static void main(String[] args) {
        var c = fresh();
        expect(NONE, c.tick(1009, true, true));
        c.onReverse(false, 1009);
        expect(NONE, c.tick(10000, true, true));
        expect(-1L, c.nextDelayMs(10000));

        c = fresh();
        c.onReverse(true, 900);
        expect(PAUSE, c.tick(1010, true, true));
        expect(NONE, c.tick(2000, true, true));
        c.onPlayback(false, 2100);
        c.onReverse(false, 2200);
        expect(NONE, c.tick(2449, true, false));
        expect(1L, c.nextDelayMs(2449));
        expect(RESUME, c.tick(2450, true, false));
        expect(NONE, c.tick(3000, true, false));

        c = fresh();
        expect(NONE, c.tick(1010, true, false));
        c.onReverse(false, 2000);
        expect(NONE, c.tick(3000, true, false));

        c = fresh();
        c.configure(false);
        expect(NONE, c.tick(1010, true, true));
        c.configure(true);
        expect(NONE, c.tick(5000, true, true));
        c.onReverse(false, 5100);
        c.onReverse(true, 5200);
        expect(PAUSE, c.tick(6200, true, true));

        c = paused();
        c.cancel();
        c.onReverse(false, 2000);
        expect(NONE, c.tick(3000, true, false));

        c = fresh();
        c.cancel();
        expect(NONE, c.tick(1010, true, true));

        c = fresh();
        expect(PAUSE, c.tick(1010, true, true));
        c.onReverse(false, 1100);
        expect(NONE, c.tick(3510, true, true));
        c.onPlayback(false, 3600);
        expect(NONE, c.tick(3700, true, false));

        c = fresh();
        expect(PAUSE, c.tick(1010, true, true));
        c.onReverse(false, 1100);
        c.onPlayback(false, 3600);
        expect(NONE, c.tick(3700, true, false));

        c = fresh();
        expect(PAUSE, c.tick(1010, true, true));
        c.onReverse(false, 1050);
        expect(NONE, c.tick(1350, true, true));
        c.onPlayback(false, 1400);
        expect(RESUME, c.tick(1400, true, false));

        c = paused();
        c.onReverse(false, 1200);
        c.onReverse(true, 1300);
        expect(NONE, c.tick(2000, true, false));
        c.onReverse(false, 2100);
        expect(RESUME, c.tick(2350, true, false));

        c = paused();
        expect(NONE, c.tick(1200, false, false));
        c.onReverse(false, 1300);
        expect(NONE, c.tick(2000, true, false));

        c = paused();
        c.onPlayback(true, 1200);
        c.onPlayback(false, 1300);
        c.onReverse(false, 1400);
        expect(NONE, c.tick(2000, true, false));

        c = paused();
        c.reset();
        c.onReverse(false, 1400);
        expect(NONE, c.tick(2000, true, false));

        c = paused();
        c.configure(false);
        c.onReverse(false, 1400);
        expect(NONE, c.tick(2000, true, false));

        c = fresh();
        expect(PAUSE, c.tick(1010, true, true));
        c.onPlayback(false, 1000);
        c.onReverse(false, 1100);
        expect(NONE, c.tick(1400, true, true));

        System.out.println("Reverse pause: " + checks + " assertions passed");
    }
}
