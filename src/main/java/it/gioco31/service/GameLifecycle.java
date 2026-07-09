package it.gioco31.service;

import it.gioco31.GameConstants;
import it.gioco31.model.GameState;
import it.gioco31.model.Player;

public final class GameLifecycle {
    private GameLifecycle() {}

    /**
     * Prepara e avvia una nuova partita con il mazziere previsto dalla
     * rotazione ({@code nextMatchDealerIndex}). Ritorna false, senza avviare
     * il round né consumare la rotazione, se i giocatori presenti sono meno
     * del minimo richiesto.
     */
    public static boolean startMatch(GameState s, ThirtyOneEngine engine) {
        resetMatchState(s);
        if (preparePlayersForNewMatch(s) < GameConstants.MIN_PLAYERS) return false;

        int dealerIndex = s.getNextMatchDealerIndex();
        s.setDealerIndex(dealerIndex);
        // Rotazione: la partita successiva parte dal giocatore attivo dopo il dealer
        s.setNextMatchDealerIndex(nextActiveFrom(s, dealerIndex));
        engine.startRound(s);
        return true;
    }

    public static void resetMatchState(GameState s) {
        s.setWinnerIndex(null);
        s.clearAllNotices();
        s.clearRoundResult();
        s.setTurnDeadlineMs(0);
        s.setPendingDraw(null);
        s.setFinalTurnsRemaining(0);
        s.setKnockerIndex(null);
    }

    public static int preparePlayersForNewMatch(GameState s) {
        int joined = 0;
        for (Player p : s.getPlayers()) {
            p.getHand().clear();
            p.setSpectating(false);
            if (p.isJoined()) {
                p.setEliminated(false);
                p.setLives(s.getStartingLives());
                joined++;
            } else {
                p.setLives(0);
                p.setEliminated(true);
            }
        }
        return joined;
    }

    /**
     * Returns the index of the next non-eliminated player after fromIndex (wrapping),
     * falling back to fromIndex itself when nobody is active.
     * Used to advance the match-start dealer across matches.
     */
    public static int nextActiveFrom(GameState s, int fromIndex) {
        Integer next = s.nextActiveIndexAfter(fromIndex);
        if (next != null) return next;
        int n = s.getPlayers().size();
        return (n <= 0) ? 0 : fromIndex % n;
    }
}
