package it.gioco31.model;

public enum Rank {
    ASSO(11, "Asso"),
    DUE(2, "2"), TRE(3, "3"), QUATTRO(4, "4"), CINQUE(5, "5"), SEI(6, "6"), SETTE(7, "7"),
    FANTE(10, "Fante"), CAVALLO(10, "Cavallo"), RE(10, "Re");

    private final int value;
    private final String label;

    Rank(int value, String label) {
        this.value = value;
        this.label = label;
    }

    public int value() { return value; }
    public String label() { return label; }
}
