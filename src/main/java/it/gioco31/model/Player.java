package it.gioco31.model;

import it.gioco31.GameConstants;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public final class Player implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private final List<Card> hand = new ArrayList<>(3);
    private int lives;
    private boolean eliminated;
    private boolean joined;

    public Player(String name, int lives) {
        setName(name);
        this.lives = lives;
    }

    public String getName() { return name; }

    public void setName(String name) {
        if (name == null) throw new IllegalArgumentException("Nome nullo.");
        String n = name.trim();

        if (n.length() < GameConstants.NAME_MIN_LEN) throw new IllegalArgumentException("Nome troppo corto.");
        if (n.length() > GameConstants.NAME_MAX_LEN)
            throw new IllegalArgumentException("Nome troppo lungo (max " + GameConstants.NAME_MAX_LEN + ").");

        if (!n.matches(GameConstants.NAME_REGEX)) {
            throw new IllegalArgumentException("Nome non valido: usa lettere/numeri/spazio/_/-.");
        }
        this.name = n;
    }

    public List<Card> getHand() { return hand; }

    public int getLives() { return lives; }
    public void setLives(int lives) { this.lives = lives; }

    public boolean isEliminated() { return eliminated; }
    public void setEliminated(boolean eliminated) { this.eliminated = eliminated; }

    public boolean isJoined() { return joined; }
    public void setJoined(boolean joined) { this.joined = joined; }

    public static final class BestSuitScore {
        private final Suit suit;   // null se mano vuota
        private final int value;

        public BestSuitScore(Suit suit, int value) {
            this.suit = suit;
            this.value = value;
        }

        public Suit getSuit() { return suit; }
        public int getValue() { return value; }
    }

    public BestSuitScore bestSameSuitScore() {
        if (hand.isEmpty()) return new BestSuitScore(null, 0);

        Suit[] suits = Suit.values();
        int[] sums = new int[suits.length];

        for (Card c : hand) {
            if (c == null) continue;
            Suit s = c.suit();
            if (s == null) continue;
            sums[s.ordinal()] += c.value();
        }

        int bestIdx = 0;
        int bestVal = sums[0];
        for (int i = 1; i < sums.length; i++) {
            if (sums[i] > bestVal) {
                bestVal = sums[i];
                bestIdx = i;
            }
        }

        return new BestSuitScore(suits[bestIdx], bestVal);
    }

    public boolean hasThirtyOne() {
        return bestSameSuitScore().getValue() == 31;
    }
}
