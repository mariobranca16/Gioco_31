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
    private int nextMatchDealerIndex = 0;
    private Phase phase = Phase.WAITING_FOR_PLAYERS;

    private int finalTurnsRemaining = 0;
    private Integer knockerIndex = null;

    private Card pendingDraw = null;

    private final int startingLives;

    private Integer winnerIndex = null;

    private long noticeSeq = 0;
    private final Map<Integer, Notice> notices = new HashMap<>();

    /**
     * Scadenza (epoch ms) del turno corrente; 0 = nessun timer attivo.
     * Volatile: letta senza lock dal thread di manutenzione (sweepTurnTimeout).
     */
    private volatile long turnDeadlineMs = 0;

    private static final int MAX_EVENTS = 40;
    private long eventSeq = 0;
    private final ArrayDeque<Event> events = new ArrayDeque<>();

    private long roundResultSeq = 0;
    private RoundResult roundResult = null;

    /**
     * Numero di versione dello stato: cresce a ogni broadcast così il client
     * può scartare i frame arrivati fuori ordine. bumpSeq() va invocato solo
     * tenendo il lock della stanza.
     */
    private long stateSeq = 0;

    /** Voce del registro mosse, visibile a tutti i giocatori. */
    public static final class Event implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private final long id;
        private final long atMs;
        private final String message;

        Event(long id, long atMs, String message) {
            this.id = id;
            this.atMs = atMs;
            this.message = message;
        }

        public long getId() { return id; }
        public long getAtMs() { return atMs; }
        public String getMessage() { return message; }
    }

    /** Mano rivelata di un giocatore alla fine di un round. */
    public static final class RevealedHand implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private final int index;
        private final String name;
        private final int score;
        private final boolean lostLife;
        private final boolean eliminated;
        private final List<Card> cards;

        public RevealedHand(int index, String name, int score,
                            boolean lostLife, boolean eliminated, List<Card> cards) {
            this.index = index;
            this.name = name;
            this.score = score;
            this.lostLife = lostLife;
            this.eliminated = eliminated;
            this.cards = List.copyOf(cards);
        }

        public int getIndex() { return index; }
        public String getName() { return name; }
        public int getScore() { return score; }
        public boolean isLostLife() { return lostLife; }
        public boolean isEliminated() { return eliminated; }
        public List<Card> getCards() { return cards; }
    }

    /** Esito di un round concluso: messaggio e mani rivelate di tutti. */
    public static final class RoundResult implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private final long id;
        private final long atMs;
        private final String message;
        private final List<RevealedHand> hands;

        RoundResult(long id, long atMs, String message, List<RevealedHand> hands) {
            this.id = id;
            this.atMs = atMs;
            this.message = message;
            this.hands = List.copyOf(hands);
        }

        public long getId() { return id; }
        public long getAtMs() { return atMs; }
        public String getMessage() { return message; }
        public List<RevealedHand> getHands() { return hands; }
    }

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

    public GameState(List<Player> players, int startingLives) {
        this.players = players;
        this.startingLives = Math.max(1, startingLives);
    }

    public List<Player> getPlayers() { return players; }

    public Deck getDeck() { return deck; }
    public void setDeck(Deck deck) { this.deck = deck; }

    public Deque<Card> getDiscard() { return discard; }

    public int getDealerIndex() { return dealerIndex; }
    public void setDealerIndex(int dealerIndex) { this.dealerIndex = dealerIndex; }

    public int getNextMatchDealerIndex() { return nextMatchDealerIndex; }
    public void setNextMatchDealerIndex(int nextMatchDealerIndex) { this.nextMatchDealerIndex = nextMatchDealerIndex; }

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

    public int getStartingLives() { return startingLives; }

    public Integer getWinnerIndex() { return winnerIndex; }
    public void setWinnerIndex(Integer winnerIndex) { this.winnerIndex = winnerIndex; }

    /** Indice del prossimo giocatore non eliminato dopo fromIndex (con wrap), o null se nessuno. */
    public Integer nextActiveIndexAfter(int fromIndex) {
        int n = players.size();
        for (int step = 1; step <= n; step++) {
            int i = (fromIndex + step) % n;
            if (!players.get(i).isEliminated()) return i;
        }
        return null;
    }

    /** Indice del primo giocatore non eliminato (a partire da 0), o null se nessuno. */
    public Integer firstActiveIndex() {
        return nextActiveIndexAfter(players.size() - 1);
    }

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

    public long getTurnDeadlineMs() { return turnDeadlineMs; }
    public void setTurnDeadlineMs(long turnDeadlineMs) { this.turnDeadlineMs = turnDeadlineMs; }

    public void addEvent(String message) {
        if (message == null || message.isBlank()) return;
        events.addLast(new Event(++eventSeq, System.currentTimeMillis(), message));
        while (events.size() > MAX_EVENTS) events.removeFirst();
    }

    public List<Event> getEvents() {
        return List.copyOf(events);
    }

    public void setRoundResult(String message, List<RevealedHand> hands) {
        this.roundResult = new RoundResult(++roundResultSeq, System.currentTimeMillis(), message, hands);
    }

    public RoundResult getRoundResult() { return roundResult; }
    public void clearRoundResult() { roundResult = null; }

    public long getStateSeq() { return stateSeq; }

    /** Incrementa e ritorna la versione dello stato; solo col lock della stanza. */
    public long bumpSeq() { return ++stateSeq; }
}
