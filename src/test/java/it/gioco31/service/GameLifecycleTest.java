package it.gioco31.service;

import it.gioco31.model.Card;
import it.gioco31.model.GameState;
import it.gioco31.model.Phase;
import it.gioco31.model.Player;
import it.gioco31.model.Rank;
import it.gioco31.model.Suit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameLifecycleTest {

    private static GameState newGame(int nPlayers) {
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < nPlayers; i++) players.add(new Player("Player" + (i + 1), 3));
        return new GameState(players, 42L, 3);
    }

    @Test
    void resetMatchStateClearsRoundData() {
        GameState s = newGame(2);
        s.setWinnerIndex(1);
        s.setPendingDraw(new Card(Suit.DENARI, Rank.ASSO));
        s.setFinalTurnsRemaining(2);
        s.setKnockerIndex(0);
        s.setNoticeForPlayer(0, "msg");
        s.setRoundResult("esito", List.of());
        s.setTurnDeadlineMs(123L);

        GameLifecycle.resetMatchState(s);

        assertNull(s.getWinnerIndex());
        assertNull(s.getPendingDraw());
        assertEquals(0, s.getFinalTurnsRemaining());
        assertNull(s.getKnockerIndex());
        assertNull(s.getNoticeForPlayer(0));
        assertNull(s.getRoundResult());
        assertEquals(0, s.getTurnDeadlineMs());
    }

    @Test
    void preparePlayersRestoresJoinedAndExcludesEmptySlots() {
        GameState s = newGame(3);
        Player joined1 = s.getPlayers().get(0);
        Player empty = s.getPlayers().get(1);
        Player joined2 = s.getPlayers().get(2);
        joined1.setJoined(true);
        joined2.setJoined(true);
        joined1.setEliminated(true);
        joined1.setLives(0);
        joined2.setSpectating(true);

        int count = GameLifecycle.preparePlayersForNewMatch(s);

        assertEquals(2, count);
        assertFalse(joined1.isEliminated());
        assertEquals(3, joined1.getLives());
        assertFalse(joined2.isSpectating());
        assertTrue(empty.isEliminated());
        assertEquals(0, empty.getLives());
    }

    @Test
    void startMatchUsesStoredRotationAndAdvancesIt() {
        GameState s = newGame(3);
        for (Player p : s.getPlayers()) p.setJoined(true);
        s.setDealerIndex(2);           // mazziere della partita precedente
        s.setNextMatchDealerIndex(0);  // rotazione memorizzata

        assertTrue(GameLifecycle.startMatch(s, new ThirtyOneEngine()));

        assertEquals(0, s.getDealerIndex(), "il mazziere viene dalla rotazione, non dal vecchio dealerIndex");
        assertEquals(1, s.getNextMatchDealerIndex());
        assertEquals(Phase.PLAYING, s.getPhase());
    }

    @Test
    void failedStartKeepsRotationForTheNextAttempt() {
        GameState s = newGame(3);
        s.getPlayers().get(0).setJoined(true); // un solo giocatore: non si parte
        s.setDealerIndex(2);
        s.setNextMatchDealerIndex(0);

        assertFalse(GameLifecycle.startMatch(s, new ThirtyOneEngine()));

        // il secondo giocatore entra e si riparte: la rotazione non va persa
        s.getPlayers().get(1).setJoined(true);
        assertTrue(GameLifecycle.startMatch(s, new ThirtyOneEngine()));
        assertEquals(0, s.getDealerIndex(),
                "dopo un tentativo fallito il mazziere della rotazione resta quello previsto");
    }

    @Test
    void nextActiveFromWrapsAndSkipsEliminated() {
        GameState s = newGame(4);
        s.getPlayers().get(1).setEliminated(true);
        s.getPlayers().get(2).setEliminated(true);

        assertEquals(3, GameLifecycle.nextActiveFrom(s, 0));
        assertEquals(0, GameLifecycle.nextActiveFrom(s, 3));
    }
}
