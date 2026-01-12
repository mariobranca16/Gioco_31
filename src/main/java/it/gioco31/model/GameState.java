package it.gioco31.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GameState implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final List<Player> players;
    private Deck deck;
    private final Deque<Card> discard = new ArrayDeque<>();

    private int dealerIndex = 0;
    private int currentIndex = 0;
    private Phase phase = Phase.WAITING_FOR_PLAYERS;

    private int finalTurnsRemaining = 0;
    private Integer knockerIndex = null;

    private Card pendingDraw = null;

    private final long seed;
    private final int startingLives;

    private Integer winnerIndex = null;

    private long noticeSeq = 0;
    private final Map<Integer, Notice> notices = new HashMap<>();

    public static final class Notice implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private final long id;
        private final String message;

        public Notice(long id, String message) {
            this.id = id;
            this.message = message;
        }

        public long getId() { return id; }
        public String getMessage() { return message; }
    }

    public GameState(List<Player> players, long seed, int startingLives) {
        this.players = players;
        this.seed = seed;
        this.startingLives = Math.max(1, startingLives);
    }

    public GameState(List<Player> players, long seed) {
        this.players = players;
        this.seed = seed;
        int sl = 3;
        if (players != null && !players.isEmpty() && players.get(0) != null) {
            sl = Math.max(1, players.get(0).getLives());
        }
        this.startingLives = sl;
    }

    public List<Player> getPlayers() { return players; }

    public Deck getDeck() { return deck; }
    public void setDeck(Deck deck) { this.deck = deck; }

    public Deque<Card> getDiscard() { return discard; }

    public int getDealerIndex() { return dealerIndex; }
    public void setDealerIndex(int dealerIndex) { this.dealerIndex = dealerIndex; }

    public int getCurrentIndex() { return currentIndex; }
    public void setCurrentIndex(int currentIndex) { this.currentIndex = currentIndex; }

    public Phase getPhase() { return phase; }
    public void setPhase(Phase phase) { this.phase = phase; }

    public int getFinalTurnsRemaining() { return finalTurnsRemaining; }
    public void setFinalTurnsRemaining(int finalTurnsRemaining) { this.finalTurnsRemaining = finalTurnsRemaining; }

    public Integer getKnockerIndex() { return knockerIndex; }
    public void setKnockerIndex(Integer knockerIndex) { this.knockerIndex = knockerIndex; }

    public Card getPendingDraw() { return pendingDraw; }
    public void setPendingDraw(Card pendingDraw) { this.pendingDraw = pendingDraw; }

    public long getSeed() { return seed; }

    public int getStartingLives() { return startingLives; }

    public Integer getWinnerIndex() { return winnerIndex; }
    public void setWinnerIndex(Integer winnerIndex) { this.winnerIndex = winnerIndex; }

    public Notice getNoticeForPlayer(int playerIndex) {
        return notices.get(playerIndex);
    }

    public void setNoticeForPlayer(int playerIndex, String message) {
        if (message == null || message.isBlank()) return;
        long id = ++noticeSeq;
        notices.put(playerIndex, new Notice(id, message));
    }

    public void clearNoticeForPlayer(int playerIndex, long noticeId) {
        Notice n = notices.get(playerIndex);
        if (n != null && n.getId() == noticeId) {
            notices.remove(playerIndex);
        }
    }

    public void clearAllNotices() {
        notices.clear();
    }
}
