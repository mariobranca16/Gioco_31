package it.gioco31.service;

import it.gioco31.model.GameState;
import it.gioco31.model.Player;

public final class GameLifecycle {
    private GameLifecycle() {}

    public static void resetMatchState(GameState s) {
        s.setWinnerIndex(null);
        s.clearAllNotices();
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
     * Returns the index of the next non-eliminated player after fromIndex (wrapping).
     * Used to advance the match-start dealer across matches.
     */
    public static int nextActiveFrom(GameState s, int fromIndex) {
        int n = s.getPlayers().size();
        if (n <= 0) return 0;
        for (int step = 1; step <= n; step++) {
            int i = (fromIndex + step) % n;
            if (!s.getPlayers().get(i).isEliminated()) return i;
        }
        return fromIndex % Math.max(1, n);
    }
}
