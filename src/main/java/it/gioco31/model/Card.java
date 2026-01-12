package it.gioco31.model;

import java.io.Serial;
import java.io.Serializable;

public record Card(Suit suit, Rank rank) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public int value() { return rank.value(); }
    public String label() { return rank.label() + " di " + suit.label(); }
}
