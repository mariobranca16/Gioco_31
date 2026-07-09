package it.gioco31;

public final class GameConstants {

    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 6;

    public static final int DEFAULT_LIVES = 3;
    public static final int MIN_LIVES = 1;
    public static final int MAX_LIVES = 9;

    public static final int DEFAULT_ROOM_ID_LEN = 4;
    public static final int ROOM_ID_MIN_LEN = 4;
    public static final int ROOM_ID_MAX_LEN = 8;

    public static final int NAME_MIN_LEN = 2;
    public static final int NAME_MAX_LEN = 16;
    public static final String NAME_REGEX = "[\\p{L}0-9 _-]{2,16}";

    public static final long ROOM_STALE_MS = 6L * 60 * 60 * 1000; // dopo 6 ore

    /** Tempo concesso a un giocatore disconnesso per riconnettersi prima di essere rimosso. */
    public static final long DISCONNECT_GRACE_MS = 45_000L;

    /**
     * Grazia estesa tra il join HTTP e la prima apertura del WebSocket:
     * il primo caricamento pagina (tab mobile in background, rete lenta)
     * può richiedere molto più dei 45 secondi standard.
     */
    public static final long FIRST_CONNECT_GRACE_MS = 180_000L;

    /** Tempo massimo per giocare il proprio turno, poi viene giocato d'ufficio. */
    public static final long TURN_TIMEOUT_MS = 90_000L;

    private GameConstants() {}
}
