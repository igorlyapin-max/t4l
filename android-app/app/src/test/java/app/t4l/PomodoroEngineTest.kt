package app.t4l

import org.junit.Assert.assertEquals
import org.junit.Test

class PomodoroEngineTest {
    @Test fun simpleModeAlternatesAndPauses() {
        val breakState = PomodoroEngine.next(PomodoroState(status = PomodoroStatus.RUNNING))
        assertEquals(PomodoroPhase.SHORT_BREAK, breakState.phase)
        assertEquals(PomodoroStatus.PAUSED, breakState.status)
        assertEquals(5 * 60_000L, breakState.pausedRemainingMs)
        assertEquals(PomodoroPhase.WORK, PomodoroEngine.next(breakState).phase)
    }

    @Test fun classicFourthRoundUsesLongBreakAndResetsRound() {
        val fourth = PomodoroState(mode = PomodoroMode.CLASSIC, phase = PomodoroPhase.WORK, workRound = 4, status = PomodoroStatus.RUNNING)
        val longBreak = PomodoroEngine.next(fourth)
        assertEquals(PomodoroPhase.LONG_BREAK, longBreak.phase)
        assertEquals(15 * 60_000L, longBreak.pausedRemainingMs)
        val nextWork = PomodoroEngine.next(longBreak)
        assertEquals(PomodoroPhase.WORK, nextWork.phase)
        assertEquals(1, nextWork.workRound)
    }

    @Test fun activeConfigurationPreservesCurrentCountdownAndAppliesToNextPhase() {
        val running = PomodoroState(
            mode = PomodoroMode.SIMPLE,
            phase = PomodoroPhase.WORK,
            status = PomodoroStatus.RUNNING,
            workRound = 2,
            endsAtEpochMs = 99_000,
            pausedRemainingMs = 42_000,
        )

        val configured = PomodoroEngine.configure(running, PomodoroMode.CLASSIC, 50, 10, 25)

        assertEquals(PomodoroStatus.RUNNING, configured.status)
        assertEquals(PomodoroPhase.WORK, configured.phase)
        assertEquals(99_000, configured.endsAtEpochMs)
        assertEquals(42_000, configured.pausedRemainingMs)
        assertEquals(10 * 60_000L, PomodoroEngine.next(configured).pausedRemainingMs)
    }

    @Test fun idleConfigurationStartsFreshWorkPhase() {
        val idleBreak = PomodoroState(phase = PomodoroPhase.SHORT_BREAK, status = PomodoroStatus.IDLE, workRound = 3)

        val configured = PomodoroEngine.configure(idleBreak, PomodoroMode.CLASSIC, 45, 8, 20)

        assertEquals(PomodoroPhase.WORK, configured.phase)
        assertEquals(1, configured.workRound)
        assertEquals(45 * 60_000L, configured.pausedRemainingMs)
    }
}
