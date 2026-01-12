package it.gioco31.room;

import it.gioco31.model.GameState;
import it.gioco31.model.Phase;
import it.gioco31.model.Player;
import it.gioco31.service.ThirtyOneEngine;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public final class GameRoom {
    private final String roomId;
    private final GameState state;
    private final ThirtyOneEngine engine = new ThirtyOneEngine();
    private final ReentrantLock lock = new ReentrantLock();

    private final Map<String, Integer> tokenToIndex = new HashMap<>();

    private volatile long lastActivityMs = System.currentTimeMillis();

    public GameRoom(String roomId, GameState state) {
        this.roomId = roomId;
        this.state = state;
        this.state.setPhase(Phase.WAITING_FOR_PLAYERS);
        touch();
    }

    public String roomId() { return roomId; }
    public GameState state() { return state; }
    public ThirtyOneEngine engine() { return engine; }
    public ReentrantLock lock() { return lock; }

    public long getLastActivityMs() { return lastActivityMs; }

    public void touch() { lastActivityMs = System.currentTimeMillis(); }

    public boolean hasAnyJoinedPlayers() {
        for (Player p : state.getPlayers()) {
            if (p != null && p.isJoined()) return true;
        }
        return false;
    }

    public void bindToken(String token, int index) {
        lock.lock();
        try {
            tokenToIndex.put(token, index);
            touch();
        } finally {
            lock.unlock();
        }
    }

    public Integer indexByToken(String token) {
        lock.lock();
        try { return tokenToIndex.get(token); }
        finally { lock.unlock(); }
    }

    public void releaseTokenAndFreeSlot(String token) {
        lock.lock();
        try {
            Integer idx = tokenToIndex.remove(token);
            if (idx == null) return;
            if (idx < 0 || idx >= state.getPlayers().size()) return;

            Player p = state.getPlayers().get(idx);

            if (state.getPhase() != Phase.WAITING_FOR_PLAYERS && state.getPhase() != Phase.GAME_OVER) {
                if (state.getCurrentIndex() == idx && state.getPendingDraw() != null) {
                    state.getDiscard().push(state.getPendingDraw());
                    state.setPendingDraw(null);
                }

                p.getHand().clear();
                p.setLives(0);
                p.setEliminated(true);
            } else {
                p.getHand().clear();
                p.setEliminated(false);
            }

            p.setJoined(false);
            p.setName("Slot " + (idx + 1));

            if (state.getPhase() != Phase.WAITING_FOR_PLAYERS
                    && state.getPhase() != Phase.GAME_OVER
                    && state.getCurrentIndex() == idx) {

                int n = state.getPlayers().size();
                Integer next = null;
                for (int step = 1; step <= n; step++) {
                    int i = (idx + step) % n;
                    if (!state.getPlayers().get(i).isEliminated()) { next = i; break; }
                }
                if (next != null) state.setCurrentIndex(next);
                else state.setPhase(Phase.GAME_OVER);
            }

            touch();

        } finally {
            lock.unlock();
        }
    }
}
