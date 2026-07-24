package it.gioco31.room;

import it.gioco31.model.GameState;
import it.gioco31.model.Phase;
import org.junit.jupiter.api.Test;

import static it.gioco31.testutil.Fixtures.newRoom;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Timer anti-AFK: un giocatore connesso ma inattivo non deve poter
 * bloccare la partita. Allo scadere del tempo il suo turno viene
 * giocato d'ufficio dallo sweeper.
 */
class GameRoomTurnTimeoutTest {

    @Test
    void expiredTurnIsPlayedAutomaticallyAndTimerRearmed() {
        GameRoom room = newRoom(2);
        GameState s = room.state();
        room.engine().startRound(s);
        int current = s.getCurrentIndex();

        assertFalse(room.sweepTurnTimeout(System.currentTimeMillis()),
                "entro il tempo massimo non succede nulla");
        assertEquals(current, s.getCurrentIndex());

        s.setTurnDeadlineMs(System.currentTimeMillis() - 1);

        assertTrue(room.sweepTurnTimeout(System.currentTimeMillis()));
        assertNotEquals(current, s.getCurrentIndex(), "il turno passa al giocatore successivo");
        assertTrue(s.getTurnDeadlineMs() > System.currentTimeMillis(),
                "il timer è riarmato per il nuovo turno");

        boolean recorded = s.getEvents().stream()
                .anyMatch(e -> e.getMessage().contains("Tempo scaduto"));
        assertTrue(recorded, "il timeout va registrato nel registro mosse");
    }

    @Test
    void noTimeoutOutsideOfPlay() {
        GameRoom room = newRoom(2);
        GameState s = room.state();
        assertEquals(Phase.WAITING_FOR_PLAYERS, s.getPhase());

        s.setTurnDeadlineMs(1L); // scadenza fittizia rimasta appesa

        assertFalse(room.sweepTurnTimeout(System.currentTimeMillis()));
    }

    @Test
    void timeoutDuringKnockFinalTurnResolvesRound() {
        GameRoom room = newRoom(2);
        GameState s = room.state();
        room.engine().startRound(s);

        room.engine().knock(s);
        s.setTurnDeadlineMs(System.currentTimeMillis() - 1);

        assertTrue(room.sweepTurnTimeout(System.currentTimeMillis()));
        assertNotEquals(Phase.KNOCK_CALLED, s.getPhase(),
                "il turno finale giocato d'ufficio deve risolvere la bussata");
    }
}
