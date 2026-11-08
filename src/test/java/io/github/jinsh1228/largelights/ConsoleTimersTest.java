package io.github.jinsh1228.largelights;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ConsoleTimersTest {
    private static final ConsoleSettings.Timer[] TIMERS = {
            new ConsoleSettings.Timer(true, 12000, 0),   // 18:00
            new ConsoleSettings.Timer(true, 18000, 3),   // 0:00
            new ConsoleSettings.Timer(false, 13000, 1),  // 19:00, off
            new ConsoleSettings.Timer(true, 1000, 2)};   // 7:00

    @Test
    void aTimeIsPassedOnceAsTheDayGoesBy() {
        assertEquals(0, ConsoleTimers.passed(TIMERS, 11990, 12000));
        assertEquals(-1, ConsoleTimers.passed(TIMERS, 12000, 12010));
        // Days later, the same time of day.
        assertEquals(0, ConsoleTimers.passed(TIMERS, 3 * 24000 + 11999, 3 * 24000 + 12000));
        // Past midnight into the next day.
        assertEquals(3, ConsoleTimers.passed(TIMERS, 23990, 25000));
    }

    @Test
    void timersOffAreSkipped() {
        assertEquals(-1, ConsoleTimers.passed(TIMERS, 12500, 13500));
    }

    @Test
    void aJumpRecallsTheLastTimerPassed() {
        // Sleeping from 19:00 to 6:00 passes 0:00 (off scene) but not 7:00.
        assertEquals(1, ConsoleTimers.passed(TIMERS, 13000, 24000));
        // A whole day passes everything; the last time passed is 18:00 again.
        assertEquals(0, ConsoleTimers.passed(TIMERS, 12000, 36000));
    }

    @Test
    void goingBackRecallsNothing() {
        assertEquals(-1, ConsoleTimers.passed(TIMERS, 13000, 11000));
        assertEquals(-1, ConsoleTimers.passed(TIMERS, 13000, 13000));
    }

    @Test
    void timersReadAsAClock() {
        assertEquals(18, TIMERS[0].hour());
        assertEquals(0, TIMERS[0].minute());
        assertEquals(0, TIMERS[1].hour());
        assertEquals(30, new ConsoleSettings.Timer(true, 500, 0).minute());
        assertEquals(6, new ConsoleSettings.Timer(true, 0, 0).hour());
    }
}
