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
}
