package it.gioco31.service;

import it.gioco31.model.GameState;
import it.gioco31.model.Player;

public final class GameLifecycle {
    private GameLifecycle() {}

    public static int countJoined(GameState s) {
        int joined = 0;
        for (Player p : s.getPlayers()) if (p.isJoined()) joined++;
        return joined;
    }

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
}
