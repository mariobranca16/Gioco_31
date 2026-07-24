package it.gioco31.service;

import it.gioco31.model.Card;
import it.gioco31.model.Deck;
import it.gioco31.model.GameState;
import it.gioco31.model.Phase;
import it.gioco31.model.Player;
import it.gioco31.model.Rank;
import it.gioco31.model.Suit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static it.gioco31.testutil.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ThirtyOneEngineTest {

    private final ThirtyOneEngine engine = new ThirtyOneEngine();

    /** Completa il turno finale del giocatore corrente: pesca dal mazzo e rifiuta. */
    private void playFinalTurn(GameState s) {
        engine.drawPendingFromDeck(s);
        engine.rejectDraw(s);
    }

    // ---------- startRound ----------

    @Test
    void startRoundDealsThreeCardsToEachActivePlayerAndOneDiscard() {
        GameState s = newGame(3);
        engine.startRound(s);

        for (Player p : s.getPlayers()) assertEquals(3, p.getHand().size());
        assertEquals(1, s.getDiscard().size());
        assertEquals(40 - 9 - 1, s.getDeck().size());
        assertEquals(Phase.PLAYING, s.getPhase());
        assertNull(s.getPendingDraw());
        assertNull(s.getKnockerIndex());
        assertEquals(0, s.getFinalTurnsRemaining());
    }

    @Test
    void startRoundSkipsEliminatedPlayers() {
        GameState s = newGame(3);
        s.getPlayers().get(1).setEliminated(true);
        engine.startRound(s);

        assertEquals(3, s.getPlayers().get(0).getHand().size());
        assertEquals(0, s.getPlayers().get(1).getHand().size());
        assertEquals(3, s.getPlayers().get(2).getHand().size());
    }

    @Test
    void startRoundSetsCurrentToNextActiveAfterDealer() {
        GameState s = newGame(3);
        s.setDealerIndex(0);
        engine.startRound(s);
        assertEquals(1, s.getCurrentIndex());

        s.getPlayers().get(1).setEliminated(true);
        engine.startRound(s);
        assertEquals(2, s.getCurrentIndex());
    }

    // ---------- pesca ----------

    @Test
    void drawFromDeckSetsPendingAndShrinksDeck() {
        GameState s = newGame(2);
        engine.startRound(s);
        int before = s.getDeck().size();

        engine.drawPendingFromDeck(s);

        assertNotNull(s.getPendingDraw());
        assertEquals(before - 1, s.getDeck().size());
    }

    @Test
    void cannotDrawTwice() {
        GameState s = newGame(2);
        engine.startRound(s);
        engine.drawPendingFromDeck(s);

        assertThrows(IllegalStateException.class, () -> engine.drawPendingFromDeck(s));
        assertThrows(IllegalStateException.class, () -> engine.drawPendingFromDiscard(s));
    }

    @Test
    void drawFromDiscardTakesTopCard() {
        GameState s = newGame(2);
        engine.startRound(s);
        Card top = s.getDiscard().peek();

        engine.drawPendingFromDiscard(s);

        assertEquals(top, s.getPendingDraw());
        assertTrue(s.getDiscard().isEmpty());
    }

    @Test
    void drawFromEmptyDiscardThrows() {
        GameState s = newGame(2);
        engine.startRound(s);
        s.getDiscard().clear();

        assertThrows(IllegalStateException.class, () -> engine.drawPendingFromDiscard(s));
    }

    // ---------- scarto / tieni ----------

    @Test
    void takeAndDiscardSwapsCardAndAdvancesTurn() {
        GameState s = newGame(2);
        engine.startRound(s);
        int me = s.getCurrentIndex();
        Player p = s.getPlayers().get(me);
        hand10(p);
        Card drawn = c(Suit.SPADE, Rank.SETTE);
        s.setPendingDraw(drawn);
        Card discarded = p.getHand().get(1);

        boolean made31 = engine.takeAndDiscard(s, 1);

        assertFalse(made31);
        assertEquals(drawn, p.getHand().get(1));
        assertEquals(discarded, s.getDiscard().peek());
        assertNull(s.getPendingDraw());
        assertNotEquals(me, s.getCurrentIndex());
    }

    @Test
    void takeAndDiscardReturnsTrueOnThirtyOne() {
        GameState s = newGame(2);
        engine.startRound(s);
        Player p = s.getPlayers().get(s.getCurrentIndex());
        setHand(p, c(Suit.DENARI, Rank.ASSO), c(Suit.DENARI, Rank.RE), c(Suit.COPPE, Rank.SETTE));
        s.setPendingDraw(c(Suit.DENARI, Rank.FANTE)); // 11 + 10 + 10 = 31

        boolean made31 = engine.takeAndDiscard(s, 2);

        assertTrue(made31);
        assertTrue(p.hasThirtyOne());
    }

    @Test
    void takeAndDiscardRejectsInvalidIndex() {
        GameState s = newGame(2);
        engine.startRound(s);
        engine.drawPendingFromDeck(s);

        assertThrows(IllegalArgumentException.class, () -> engine.takeAndDiscard(s, 3));
        assertThrows(IllegalArgumentException.class, () -> engine.takeAndDiscard(s, -1));
    }

    @Test
    void rejectDrawDiscardsPendingAndAdvancesTurn() {
        GameState s = newGame(2);
        engine.startRound(s);
        int me = s.getCurrentIndex();
        engine.drawPendingFromDeck(s);
        Card pending = s.getPendingDraw();

        engine.rejectDraw(s);

        assertEquals(pending, s.getDiscard().peek());
        assertNull(s.getPendingDraw());
        assertNotEquals(me, s.getCurrentIndex());
    }

    // ---------- bussata ----------

    @Test
    void knockStartsFinalTurnsAndAdvances() {
        GameState s = newGame(3);
        engine.startRound(s);
        int knocker = s.getCurrentIndex();

        engine.knock(s);

        assertEquals(Phase.KNOCK_CALLED, s.getPhase());
        assertEquals(knocker, s.getKnockerIndex());
        assertEquals(2, s.getFinalTurnsRemaining());
        assertNotEquals(knocker, s.getCurrentIndex());

        List<GameState.Event> events = s.getEvents();
        assertTrue(events.get(events.size() - 1).getMessage().contains("ha bussato"),
                "la bussata va registrata nel registro mosse");
    }

    @Test
    void knockWithPendingDrawThrows() {
        GameState s = newGame(2);
        engine.startRound(s);
        engine.drawPendingFromDeck(s);

        assertThrows(IllegalStateException.class, () -> engine.knock(s));
    }

    @Test
    void knockerLosesLifeWhenBeaten() {
        GameState s = newGame(2);
        engine.startRound(s);
        int knocker = s.getCurrentIndex();     // 1
        int other = (knocker + 1) % 2;         // 0
        hand25(s.getPlayers().get(knocker));
        hand30(s.getPlayers().get(other));

        engine.knock(s);
        playFinalTurn(s);

        assertEquals(2, s.getPlayers().get(knocker).getLives());
        assertEquals(3, s.getPlayers().get(other).getLives());
        assertEquals(Phase.PLAYING, s.getPhase()); // nuovo round avviato
    }

    @Test
    void knockerLosesLifeWhenTied() {
        GameState s = newGame(2);
        engine.startRound(s);
        int knocker = s.getCurrentIndex();
        int other = (knocker + 1) % 2;
        hand25(s.getPlayers().get(knocker));
        setHand(s.getPlayers().get(other),
                c(Suit.COPPE, Rank.RE), c(Suit.COPPE, Rank.FANTE), c(Suit.COPPE, Rank.CINQUE)); // 25

        engine.knock(s);
        playFinalTurn(s);

        assertEquals(2, s.getPlayers().get(knocker).getLives());
        assertEquals(3, s.getPlayers().get(other).getLives());
    }

    @Test
    void lowestScoreLosesLifeWhenKnockerIsHighest() {
        GameState s = newGame(2);
        engine.startRound(s);
        int knocker = s.getCurrentIndex();
        int other = (knocker + 1) % 2;
        hand30(s.getPlayers().get(knocker));
        hand10(s.getPlayers().get(other));

        engine.knock(s);
        playFinalTurn(s);

        assertEquals(3, s.getPlayers().get(knocker).getLives());
        assertEquals(2, s.getPlayers().get(other).getLives());
    }

    @Test
    void allLowestScoresLoseLifeOnTieAmongLosers() {
        GameState s = newGame(3);
        engine.startRound(s);
        int knocker = s.getCurrentIndex(); // 1
        hand30(s.getPlayers().get(knocker));
        hand10(s.getPlayers().get(0));
        setHand(s.getPlayers().get(2),
                c(Suit.SPADE, Rank.RE), c(Suit.DENARI, Rank.DUE), c(Suit.COPPE, Rank.TRE)); // 10

        engine.knock(s);
        playFinalTurn(s); // player 2
        playFinalTurn(s); // player 0

        assertEquals(3, s.getPlayers().get(1).getLives());
        assertEquals(2, s.getPlayers().get(0).getLives());
        assertEquals(2, s.getPlayers().get(2).getLives());
    }

    @Test
    void playerWithNoLivesIsEliminatedAndLastOneStandingWins() {
        GameState s = newGame(2);
        engine.startRound(s);
        int knocker = s.getCurrentIndex();
        int other = (knocker + 1) % 2;
        s.getPlayers().get(other).setLives(1);
        hand30(s.getPlayers().get(knocker));
        hand10(s.getPlayers().get(other));

        engine.knock(s);
        playFinalTurn(s);

        assertTrue(s.getPlayers().get(other).isEliminated());
        assertEquals(Phase.GAME_OVER, s.getPhase());
        assertEquals(knocker, s.getWinnerIndex());
    }

    @Test
    void roundContinuesWithRemainingPlayersAfterElimination() {
        GameState s = newGame(3);
        engine.startRound(s);
        int knocker = s.getCurrentIndex(); // 1
        s.getPlayers().get(0).setLives(1);
        hand30(s.getPlayers().get(knocker));
        hand10(s.getPlayers().get(0));
        hand25(s.getPlayers().get(2));

        engine.knock(s);
        playFinalTurn(s); // player 2
        playFinalTurn(s); // player 0

        assertTrue(s.getPlayers().get(0).isEliminated());
        assertEquals(Phase.PLAYING, s.getPhase());
        assertNull(s.getWinnerIndex());
        // il nuovo round non distribuisce carte all'eliminato
        assertEquals(0, s.getPlayers().get(0).getHand().size());
        assertEquals(3, s.getPlayers().get(1).getHand().size());
        assertEquals(3, s.getPlayers().get(2).getHand().size());
    }

    @Test
    void knockerCannotActDuringFinalTurns() {
        GameState s = newGame(2);
        engine.startRound(s);
        engine.knock(s);
        // forza il turno sul bussatore per verificare la guardia
        s.setCurrentIndex(s.getKnockerIndex());

        assertThrows(IllegalStateException.class, () -> engine.drawPendingFromDeck(s));
    }

    // ---------- timer di turno ----------

    @Test
    void startRoundArmsTurnTimer() {
        GameState s = newGame(2);
        assertEquals(0, s.getTurnDeadlineMs());

        engine.startRound(s);

        assertTrue(s.getTurnDeadlineMs() > System.currentTimeMillis());
    }

    @Test
    void turnTimerClearedOnGameOver() {
        GameState s = newGame(2);
        engine.startRound(s);

        engine.handlePlayerRemoved(s, 0);

        assertEquals(Phase.GAME_OVER, s.getPhase());
        assertEquals(0, s.getTurnDeadlineMs());
    }

    @Test
    void forcePlayWithoutPendingDrawsAndDiscardsAndAdvances() {
        GameState s = newGame(2);
        engine.startRound(s);
        int me = s.getCurrentIndex();
        int deckBefore = s.getDeck().size();

        engine.forcePlayCurrentTurn(s);

        assertNotEquals(me, s.getCurrentIndex());
        assertEquals(deckBefore - 1, s.getDeck().size());
        assertNull(s.getPendingDraw());
    }

    @Test
    void forcePlayWithPendingDiscardsItAndAdvances() {
        GameState s = newGame(2);
        engine.startRound(s);
        int me = s.getCurrentIndex();
        engine.drawPendingFromDeck(s);
        Card pending = s.getPendingDraw();

        engine.forcePlayCurrentTurn(s);

        assertEquals(pending, s.getDiscard().peek());
        assertNull(s.getPendingDraw());
        assertNotEquals(me, s.getCurrentIndex());
    }

    @Test
    void forcePlayResolvesLastFinalTurnOfKnock() {
        GameState s = newGame(2);
        engine.startRound(s);
        int knocker = s.getCurrentIndex();
        int other = (knocker + 1) % 2;
        hand30(s.getPlayers().get(knocker));
        hand10(s.getPlayers().get(other));

        engine.knock(s);
        engine.forcePlayCurrentTurn(s); // turno finale dell'altro, giocato d'ufficio

        assertEquals(Phase.PLAYING, s.getPhase(), "il round deve risolversi e ripartire");
        assertEquals(2, s.getPlayers().get(other).getLives());
    }

    // ---------- registro mosse ----------

    @Test
    void drawsAreRecordedAsEvents() {
        GameState s = newGame(2);
        engine.startRound(s);

        engine.drawPendingFromDeck(s);

        List<GameState.Event> events = s.getEvents();
        assertTrue(events.get(events.size() - 1).getMessage().contains("pesca dal mazzo"));
    }

    // ---------- esito round (mani rivelate) ----------

    @Test
    void knockResolutionRevealsAllHands() {
        GameState s = newGame(2);
        engine.startRound(s);
        int knocker = s.getCurrentIndex();
        int other = (knocker + 1) % 2;
        hand25(s.getPlayers().get(knocker));
        hand30(s.getPlayers().get(other));
        assertNull(s.getRoundResult());

        engine.knock(s);
        playFinalTurn(s);

        GameState.RoundResult rr = s.getRoundResult();
        assertNotNull(rr, "alla risoluzione della bussata le mani vanno rivelate");
        assertEquals(2, rr.getHands().size());

        GameState.RevealedHand kh = rr.getHands().stream()
                .filter(h -> h.getIndex() == knocker).findFirst().orElseThrow();
        assertEquals(25, kh.getScore());
        assertEquals(3, kh.getCards().size());
        assertTrue(kh.isLostLife(), "il bussatore superato perde la vita");
    }

    @Test
    void instantThirtyOneRevealsHandsWhenGameContinues() {
        GameState s = newGame(3);
        engine.startRound(s);
        int winner = s.getCurrentIndex();

        engine.applyInstantThirtyOneWin(s, winner);

        GameState.RoundResult rr = s.getRoundResult();
        assertNotNull(rr);
        assertEquals(3, rr.getHands().size());
        GameState.RevealedHand wh = rr.getHands().stream()
                .filter(h -> h.getIndex() == winner).findFirst().orElseThrow();
        assertFalse(wh.isLostLife());
    }

    @Test
    void decisiveKnockRoundStillRevealsHands() {
        GameState s = newGame(2);
        engine.startRound(s);
        int knocker = s.getCurrentIndex();
        int other = (knocker + 1) % 2;
        s.getPlayers().get(other).setLives(1);
        hand30(s.getPlayers().get(knocker));
        hand10(s.getPlayers().get(other));

        engine.knock(s);
        playFinalTurn(s);

        assertEquals(Phase.GAME_OVER, s.getPhase());
        GameState.RoundResult rr = s.getRoundResult();
        assertNotNull(rr, "anche il round che decide la partita deve rivelare le mani");
        assertEquals(2, rr.getHands().size());
    }

    @Test
    void decisiveInstantThirtyOneStillRevealsHands() {
        GameState s = newGame(2);
        engine.startRound(s);
        int winner = s.getCurrentIndex();
        int other = (winner + 1) % 2;
        s.getPlayers().get(other).setLives(1);

        engine.applyInstantThirtyOneWin(s, winner);

        assertEquals(Phase.GAME_OVER, s.getPhase());
        assertNotNull(s.getRoundResult(), "anche il 31 che decide la partita deve rivelare le mani");
    }

    // ---------- 31 istantaneo ----------

    @Test
    void instantThirtyOneWinMakesOthersLoseALifeAndRotatesDealer() {
        GameState s = newGame(3);
        engine.startRound(s);
        int dealerBefore = s.getDealerIndex();
        int winner = s.getCurrentIndex();

        engine.applyInstantThirtyOneWin(s, winner);

        assertEquals(Phase.PLAYING, s.getPhase(), "dopo il 31 parte un nuovo round");
        assertEquals(3, s.getPlayers().get(winner).getLives());
        for (int i = 0; i < 3; i++) {
            if (i != winner) assertEquals(2, s.getPlayers().get(i).getLives());
        }
        assertNotEquals(dealerBefore, s.getDealerIndex(), "il dealer ruota anche dopo un 31");
    }

    @Test
    void instantThirtyOneWinEndsGameWhenOthersRunOutOfLives() {
        GameState s = newGame(2);
        engine.startRound(s);
        int winner = s.getCurrentIndex();
        int other = (winner + 1) % 2;
        s.getPlayers().get(other).setLives(1);

        engine.applyInstantThirtyOneWin(s, winner);

        assertEquals(Phase.GAME_OVER, s.getPhase());
        assertEquals(winner, s.getWinnerIndex());
        assertTrue(s.getPlayers().get(other).isEliminated());
    }

    // ---------- ricarica mazzo ----------

    @Test
    void emptyDeckIsRefilledFromDiscardKeepingTopCard() {
        GameState s = newGame(2);
        engine.startRound(s);
        s.setDeck(new Deck()); // mazzo esaurito
        s.getDiscard().clear();
        s.getDiscard().push(c(Suit.SPADE, Rank.DUE));
        s.getDiscard().push(c(Suit.BASTONI, Rank.QUATTRO));
        Card top = c(Suit.DENARI, Rank.SETTE);
        s.getDiscard().push(top);

        engine.drawPendingFromDeck(s);

        assertNotNull(s.getPendingDraw());
        assertEquals(1, s.getDiscard().size());
        assertEquals(top, s.getDiscard().peek());
        assertEquals(1, s.getDeck().size()); // 2 ricaricate - 1 pescata
    }

    @Test
    void emptyDeckWithNoRecyclableDiscardThrows() {
        GameState s = newGame(2);
        engine.startRound(s);
        s.setDeck(new Deck());
        s.getDiscard().clear();
        s.getDiscard().push(c(Suit.DENARI, Rank.SETTE));

        assertThrows(IllegalStateException.class, () -> engine.drawPendingFromDeck(s));
    }
}
