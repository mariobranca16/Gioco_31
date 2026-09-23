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

    /**
     * Tetto alle stanze vive contemporaneamente. Il registro è una mappa statica
     * e le stanze escono solo dallo sweeper dopo ROOM_STALE_MS: senza un limite,
     * chi chiama /join?action=create in un ciclo riempie l'heap. Il valore è
     * molto sopra l'uso reale e molto sotto lo spazio degli id (36^4 ≈ 1,7M),
     * così il generatore non si trova mai a cercare un id in uno spazio saturo.
     */
    public static final int MAX_ROOMS = 5_000;

    /** Tempo concesso a un giocatore disconnesso per riconnettersi prima di essere rimosso. */
    public static final long DISCONNECT_GRACE_MS = 45_000L;

    /**
     * Quanto a lungo l'host può restare disconnesso in lobby prima che il suo
     * posto venga liberato. Serve a non tenere in memoria per sempre le stanze
     * di chi le crea e chiude subito il browser: l'host non scade con la sola
     * grazia normale, ma nemmeno resta indefinitamente.
     */
    public static final long HOST_LOBBY_GRACE_MS = 30L * 60 * 1000; // 30 minuti

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
