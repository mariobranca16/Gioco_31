package it.gioco31.ws;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La finestra scorrevole del rate limit, con un orologio finto: qui la soglia
 * è verificata al messaggio esatto, senza dipendere da quanto è veloce la
 * macchina che esegue i test.
 */
class RateWindowTest {

    private static final int MAX_PER_SEC = 20;
    private static final long SECOND = 1_000_000_000L;

    @Test
    void allowsUpToTheThresholdWithinTheSameSecond() {
        RoomEndpoint.RateWindow w = new RoomEndpoint.RateWindow();
        long t = 1_000_000L;

        for (int i = 1; i <= MAX_PER_SEC; i++) {
            assertTrue(w.allow(t + i), "il messaggio " + i + " è entro soglia");
        }
        assertFalse(w.allow(t + MAX_PER_SEC + 1), "il 21° messaggio nello stesso secondo è scartato");
    }

    @Test
    void allowsAgainOnceTheWindowHasSlidPast() {
        RoomEndpoint.RateWindow w = new RoomEndpoint.RateWindow();
        long t = 1_000_000L;

        for (int i = 0; i < MAX_PER_SEC; i++) w.allow(t);
        assertFalse(w.allow(t));

        assertTrue(w.allow(t + SECOND + 1),
                "passato un secondo dal più vecchio dei venti, si riparte");
    }

    /**
     * Un messaggio rifiutato non deve consumare uno slot: se lo consumasse, la
     * voce più vecchia non invecchierebbe mai e il giocatore resterebbe zittito
     * ben oltre la finestra.
     */
    @Test
    void aRejectedMessageDoesNotConsumeASlot() {
        RoomEndpoint.RateWindow w = new RoomEndpoint.RateWindow();
        long t = 1_000_000L;

        for (int i = 0; i < MAX_PER_SEC; i++) w.allow(t);

        // Raffica di rifiuti dentro la finestra.
        for (int i = 0; i < 100; i++) assertFalse(w.allow(t + 10));

        assertTrue(w.allow(t + SECOND + 1),
                "i rifiuti non spostano la finestra: alla scadenza si torna a passare");
    }

    /**
     * Le finestre sono indipendenti: quella di un giocatore non influenza
     * quella di un altro.
     */
    @Test
    void windowsAreIndependent() {
        RoomEndpoint.RateWindow a = new RoomEndpoint.RateWindow();
        RoomEndpoint.RateWindow b = new RoomEndpoint.RateWindow();
        long t = 1_000_000L;

        for (int i = 0; i < MAX_PER_SEC; i++) a.allow(t);
        assertFalse(a.allow(t));

        assertTrue(b.allow(t), "il limite di un giocatore non tocca gli altri");
    }
}
