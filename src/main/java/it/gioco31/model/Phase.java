package it.gioco31.model;

public enum Phase {
    WAITING_FOR_PLAYERS,
    PLAYING,
    KNOCK_CALLED,
    GAME_OVER;

    /** True se la partita è in corso (round normale o turni finali dopo bussata). */
    public boolean isInPlay() {
        return this == PLAYING || this == KNOCK_CALLED;
    }
}
