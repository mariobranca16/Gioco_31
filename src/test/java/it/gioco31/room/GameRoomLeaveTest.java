package it.gioco31.room;

import it.gioco31.model.Card;
import it.gioco31.model.GameState;
import it.gioco31.model.Phase;
import it.gioco31.model.Player;
import org.junit.jupiter.api.Test;

import static it.gioco31.testutil.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Copre l'uscita di un giocatore a partita in corso (leave o disconnessione):
 * il gioco non deve mai restare bloccato e il vincitore va dichiarato
 * quando resta un solo giocatore attivo.
 */
class GameRoomLeaveTest {

    /** Completa il turno del giocatore corrente: pesca dal mazzo e rifiuta. */
    private static void playTurn(GameRoom room) {
        room.engine().drawPendingFromDeck(room.state());
        room.engine().rejectDraw(room.state());
    }

    @Test
    void leaveOfPendingFinalTurnPlayerResolvesKnockWithoutLockingOnKnocker() {
        GameRoom room = newRoom(3);
        GameState s = room.state();
        room.engine().startRound(s); // dealer 0 -> current 1

        hand10(s.getPlayers().get(0));
        hand30(s.getPlayers().get(1));
        hand25(s.getPlayers().get(2));

        room.engine().knock(s); // knocker 1, current 2, 2 turni finali

        // il giocatore 2 (di turno, deve ancora giocare) esce
        room.releaseTokenAndFreeSlot("t2");
        assertEquals(0, s.getCurrentIndex());

        // il giocatore 0 gioca il suo turno finale: il round DEVE risolversi
        playTurn(room);

        assertEquals(Phase.PLAYING, s.getPhase(),
                "dopo l'ultimo turno finale il round deve risolversi e ripartire");
        assertNotEquals(Phase.KNOCK_CALLED, s.getPhase());
        assertEquals(2, s.getPlayers().get(0).getLives(), "il punteggio più basso perde 1 vita");
        assertEquals(3, s.getPlayers().get(1).getLives());
    }

    @Test
    void leaveLeavingOneActivePlayerDeclaresWinner() {
        GameRoom room = newRoom(2);
        GameState s = room.state();
        room.engine().startRound(s); // current 1

        // il giocatore 0 (NON di turno) esce: resta un solo attivo
        room.releaseTokenAndFreeSlot("t0");

        assertEquals(Phase.GAME_OVER, s.getPhase());
        assertEquals(1, s.getWinnerIndex());
    }

    @Test
    void leaveOfKnockerStillResolvesRoundAmongRemainingPlayers() {
        GameRoom room = newRoom(3);
        GameState s = room.state();
        room.engine().startRound(s); // current 1

        hand10(s.getPlayers().get(0));
        hand30(s.getPlayers().get(1));
        hand25(s.getPlayers().get(2));

        room.engine().knock(s); // knocker 1, current 2

        // il bussatore esce durante i turni finali
        room.releaseTokenAndFreeSlot("t1");

        playTurn(room); // giocatore 2
        playTurn(room); // giocatore 0 -> risoluzione

        assertEquals(Phase.PLAYING, s.getPhase());
        assertEquals(2, s.getPlayers().get(0).getLives(), "senza bussatore perde il punteggio più basso");
        assertEquals(3, s.getPlayers().get(2).getLives());
    }

    @Test
    void leaveOfCurrentPlayerDiscardsPendingAndPassesTurn() {
        GameRoom room = newRoom(3);
        GameState s = room.state();
        room.engine().startRound(s); // current 1

        room.engine().drawPendingFromDeck(s);
        Card pending = s.getPendingDraw();

        room.releaseTokenAndFreeSlot("t1");

        assertNull(s.getPendingDraw());
        assertEquals(pending, s.getDiscard().peek());
        assertEquals(2, s.getCurrentIndex());
        assertTrue(s.getPlayers().get(1).isEliminated());
        assertFalse(s.getPlayers().get(1).isJoined());
    }

    @Test
    void leaveDuringGameIsRecordedInEvents() {
        GameRoom room = newRoom(3);
        GameState s = room.state();
        room.engine().startRound(s);

        String leaverName = s.getPlayers().get(2).getName();
        room.releaseTokenAndFreeSlot("t2");

        boolean recorded = s.getEvents().stream()
                .anyMatch(e -> e.getMessage().contains(leaverName)
                        && e.getMessage().contains("lasciato"));
        assertTrue(recorded, "l'uscita va registrata nel registro mosse");
    }

    @Test
    void spectatorLeavingMidGameDoesNotDisturbTheRound() {
        GameRoom room = newRoom(4);
        GameState s = room.state();
        // lo slot 3 è uno spettatore entrato a partita già iniziata
        Player spec = s.getPlayers().get(3);
        spec.setEliminated(true);
        spec.setSpectating(true);
        spec.setLives(0);
        spec.getHand().clear();

        room.engine().startRound(s); // dealer 0 -> current 1
        room.engine().knock(s);      // knocker 1, current 2
        int turnsBefore = s.getFinalTurnsRemaining();
        int currentBefore = s.getCurrentIndex();

        room.releaseTokenAndFreeSlot("t3");

        assertEquals(Phase.KNOCK_CALLED, s.getPhase());
        assertEquals(turnsBefore, s.getFinalTurnsRemaining());
        assertEquals(currentBefore, s.getCurrentIndex());
        GameState.Event last = s.getEvents().get(s.getEvents().size() - 1);
        assertTrue(last.getMessage().contains("ha lasciato il tavolo"),
                "lo spettatore lascia il tavolo, non la partita: " + last.getMessage());
    }

    @Test
    void leaveInWaitingPhaseJustFreesTheSlot() {
        GameRoom room = newRoom(3);
        GameState s = room.state();

        room.releaseTokenAndFreeSlot("t1");

        Player p = s.getPlayers().get(1);
        assertFalse(p.isJoined());
        assertFalse(p.isEliminated());
        assertEquals(Phase.WAITING_FOR_PLAYERS, s.getPhase());
        assertNull(room.indexByToken("t1"));
    }
}
