package it.gioco31.model;

public record Card(Suit suit, Rank rank) {
    public int value() { return rank.value(); }
    public String label() { return rank.label() + " di " + suit.label(); }
}
