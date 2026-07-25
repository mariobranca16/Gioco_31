package it.gioco31.service;

import it.gioco31.model.GameState;
import it.gioco31.model.Phase;
import it.gioco31.model.Player;
import it.gioco31.room.GameRoom;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import static it.gioco31.testutil.Concurrency.runAllTogether;
import static it.gioco31.testutil.Fixtures.newRoom;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Migliaia di partite giocate a mosse valide casuali, per verificare che le
 * invarianti del motore reggano su sequenze che nessun test scritto a mano
 * coprirebbe: le 40 carte non si perdono né si duplicano, e il turno è sempre
 * di un giocatore ancora in gioco.
 *
 * <p>Le mosse girano sotto {@code room.lock()} perché è esattamente così che le
 * invoca RoomEndpoint: <b>non</b> è un test di concorrenza (i thread qui sono
 * solo un modo per generare carico, e sono serializzati dal lock). Le corse
 * vere stanno in {@code GameRoomConcurrencyTest}.
 */
class EngineInvariantsUnderLoadTest {

    @Test
    void invariantsHoldAcrossThousandsOfRandomValidMoves() throws Exception {
        final int drivers = 8;
        final int iterations = 3000;

        GameRoom room = newRoom(4);

        Queue<String> problems = new ConcurrentLinkedQueue<>();
        AtomicLong inPlayChecks = new AtomicLong();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < drivers; t++) {
            final long seed = t; // sequenze di scelte diverse per driver, ma riproducibili
            tasks.add(() -> {
                Random rnd = new Random(seed);
                try {
                    for (int i = 0; i < iterations; i++) {
                        room.lock().lock();
                        try {
                            GameState s = room.state();
                            Phase ph = s.getPhase();

                            // Fuori dal gioco (attesa o partita finita): (ri)avvia una
                            // partita per tenere il carico in movimento.
                            if (ph == Phase.WAITING_FOR_PLAYERS || ph == Phase.GAME_OVER) {
                                GameLifecycle.startMatch(s, room.engine());
                                continue;
                            }

                            inPlayChecks.incrementAndGet();

                            int total = countCards(s);
                            if (total != 40) {
                                problems.add("carte totali = " + total + " (fase " + ph + ")");
                                break;
                            }

                            int c = s.getCurrentIndex();
                            if (c < 0 || c >= s.getPlayers().size() || s.getPlayers().get(c).isEliminated()) {
                                problems.add("currentIndex non valido o eliminato: " + c);
                                break;
                            }

                            playOneValidMove(room.engine(), s, rnd);
                        } finally {
                            room.lock().unlock();
                        }
                    }
                } catch (RuntimeException ex) {
                    // Un'azione che ci aspettiamo valida non deve mai lanciare.
                    problems.add("eccezione non gestita: " + ex);
                }
                return null;
            });
        }

        runAllTogether(tasks);

        assertTrue(problems.isEmpty(), "invarianti violate sotto carico: " + problems);
        assertTrue(inPlayChecks.get() > 0, "il driver non ha mai osservato uno stato in gioco");
    }

    /** Esegue una singola mossa valida per il giocatore di turno. */
    private static void playOneValidMove(ThirtyOneEngine engine, GameState s, Random rnd) {
        if (s.getPendingDraw() != null) {
            // Ha una carta pescata: la tiene (scartando una in mano) o la rifiuta.
            if (rnd.nextInt(4) == 0) engine.rejectDraw(s);
            else engine.keepPendingDraw(s, rnd.nextInt(3));
            return;
        }

        // Nessuna pescata: a volte bussa (solo in PLAYING), altrimenti pesca.
        boolean canDiscard = !s.getDiscard().isEmpty();
        if (s.getPhase() == Phase.PLAYING && rnd.nextInt(6) == 0) {
            engine.knock(s);
        } else if (canDiscard && rnd.nextInt(2) == 0) {
            engine.drawPendingFromDiscard(s);
        } else {
            // Con 40 carte e mani da 3 il mazzo è sempre ricaricabile dagli scarti,
            // quindi la pesca dal mazzo è sempre valida.
            engine.drawPendingFromDeck(s);
        }
    }

    private static int countCards(GameState s) {
        int total = 0;
        for (Player p : s.getPlayers()) total += p.getHand().size();
        if (s.getDeck() != null) total += s.getDeck().size();
        total += s.getDiscard().size();
        if (s.getPendingDraw() != null) total += 1;
        return total;
    }
}
