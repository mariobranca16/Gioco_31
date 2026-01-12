package it.gioco31.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

public final class Deck implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Deque<Card> cards = new ArrayDeque<>();

    public static Deck newNeapolitan40(Random rng) {
        List<Card> all = new ArrayList<>(40);
        for (Suit s : Suit.values()) {
            for (Rank r : Rank.values()) {
                all.add(new Card(s, r));
            }
        }
        Collections.shuffle(all, rng);
        Deck d = new Deck();
        for (Card c : all) d.cards.addLast(c);
        return d;
    }

    public boolean isEmpty() { return cards.isEmpty(); }
    public int size() { return cards.size(); }

    public Card draw() {
        if (cards.isEmpty()) throw new IllegalStateException("Mazzo vuoto");
        return cards.removeFirst();
    }

    public void addAllShuffled(List<Card> list, Random rng) {
        Collections.shuffle(list, rng);
        for (Card c : list) cards.addLast(c);
    }
}
