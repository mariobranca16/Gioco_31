package it.gioco31.model;

public enum Suit {
    DENARI, COPPE, BASTONI, SPADE;

    public String label() {
        return switch (this) {
            case DENARI -> "Denari";
            case COPPE -> "Coppe";
            case BASTONI -> "Bastoni";
            case SPADE -> "Spade";
        };
    }
}
