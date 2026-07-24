package it.gioco31.room;

import it.gioco31.model.GameState;
import it.gioco31.model.Phase;
import it.gioco31.model.Player;
import it.gioco31.service.GameLifecycle;
import it.gioco31.service.ThirtyOneEngine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static it.gioco31.testutil.Fixtures.newRoom;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Concorrenza reale sulla stessa GameRoom: è da qui che nascevano i bug delle
 * sessioni precedenti (ordine degli stati, corse sull'handshake, ciclo di vita).
 * I test partono i thread insieme con un latch e li attendono con timeout, così
 * un eventuale blocco fa fallire il test invece di appenderlo.
 */
class GameRoomConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 30;

    // ---------------------------------------------------------------
    // 1. Invarianti sotto carico
    // ---------------------------------------------------------------

    @Test
    void invariantsHoldUnderConcurrentValidActions() throws Exception {
        final int threads = 8;
        final int iterations = 3000;

        GameRoom room = newRoom(4);

        Queue<String> problems = new ConcurrentLinkedQueue<>();
        AtomicLong inPlayChecks = new AtomicLong();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final long seed = t; // sequenze di scelte diverse per thread, ma riproducibili
            tasks.add(() -> {
                Random rnd = new Random(seed);
                try {
                    for (int i = 0; i < iterations; i++) {
                        room.lock().lock();
                        try {
                            GameState s = room.state();
                            Phase ph = s.getPhase();

                            // Fuori dal gioco (attesa o partita finita): (ri)avvia una
                            // partita per tenere il carico in movimento. Atomico sotto lock.
                            if (ph == Phase.WAITING_FOR_PLAYERS || ph == Phase.GAME_OVER) {
                                GameLifecycle.startMatch(s, room.engine());
                                continue;
                            }

                            // In gioco: le invarianti devono valere a ogni stato a riposo.
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

    // ---------------------------------------------------------------
    // 2. Monotonia della sequenza di stato
    // ---------------------------------------------------------------

    @Test
    void concurrentBumpsProduceStrictlyIncreasingStateSeq() throws Exception {
        final int threads = 8;
        final int perThread = 2000;
        final int total = threads * perThread;

        GameRoom room = newRoom(4);
        AtomicLong order = new AtomicLong();
        // Aggiunte solo sotto room.lock(): la lista non è mai mutata in concorrenza.
        List<long[]> records = new ArrayList<>();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                for (int i = 0; i < perThread; i++) {
                    room.lock().lock();
                    try {
                        long seq = room.state().bumpSeq();     // come dentro broadcast()
                        long ticket = order.getAndIncrement(); // ordine di acquisizione del lock
                        records.add(new long[]{ticket, seq});
                    } finally {
                        room.lock().unlock();
                    }
                }
                return null;
            });
        }

        runAllTogether(tasks);

        assertEquals(total, records.size());

        // Nell'ordine di acquisizione del lock, stateSeq è strettamente crescente:
        // mai due uguali, mai decrescenti.
        records.sort(Comparator.comparingLong(r -> r[0]));
        long prev = 0;
        Set<Long> seen = new HashSet<>();
        for (long[] r : records) {
            long seq = r[1];
            assertTrue(seq > prev, "stateSeq non crescente nell'ordine del lock: " + seq + " dopo " + prev);
            assertTrue(seen.add(seq), "stateSeq duplicato: " + seq);
            prev = seq;
        }
        assertEquals(total, seen.size());
        assertEquals(1L, records.get(0)[1], "la prima sequenza vale 1");
        assertEquals(total, records.get(records.size() - 1)[1], "l'ultima sequenza vale N");
    }

    // ---------------------------------------------------------------
    // 3. Join concorrenti
    // ---------------------------------------------------------------

    @Test
    void concurrentJoinsFillEachSlotExactlyOnce() throws Exception {
        final int slots = 4;
        final int threads = 20;

        GameRoom room = emptyRoom(slots);

        Queue<Integer> assignedIdx = new ConcurrentLinkedQueue<>();
        Queue<String> assignedNames = new ConcurrentLinkedQueue<>();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final String name = "Player" + t;      // tutti distinti e validi
            final String token = UUID.randomUUID().toString();
            tasks.add(() -> {
                Integer idx = attemptJoin(room, name, token);
                if (idx != null) {
                    assignedIdx.add(idx);
                    assignedNames.add(name);
                    // il token deve puntare esattamente allo slot assegnato
                    assertEquals(idx, room.indexByToken(token));
                }
                return null;
            });
        }

        runAllTogether(tasks);

        assertEquals(slots, assignedIdx.size(), "devono riuscire esattamente " + slots + " join");
        assertEquals(Set.of(0, 1, 2, 3), new HashSet<>(assignedIdx), "occupati tutti e soli gli slot, senza duplicati");
        assertEquals(slots, new HashSet<>(assignedNames).size(), "nessun nome duplicato");

        long joined = room.state().getPlayers().stream().filter(Player::isJoined).count();
        assertEquals(slots, joined, "in stanza risultano seduti esattamente " + slots + " giocatori");
    }

    /**
     * Occupa uno slot come fa JoinRoomServlet.tryJoin: sotto il lock della stanza
     * controlla i nomi, trova il primo slot libero e lega il token. Ritorna
     * l'indice assegnato, o null se la stanza è piena o il nome è già in uso.
     */
    private static Integer attemptJoin(GameRoom room, String name, String token) {
        room.lock().lock();
        try {
            List<Player> players = room.state().getPlayers();
            for (Player p : players) {
                if (p.isJoined() && name.equalsIgnoreCase(p.getName())) return null;
            }
            int idx = -1;
            for (int i = 0; i < players.size(); i++) {
                if (!players.get(i).isJoined()) { idx = i; break; }
            }
            if (idx < 0) return null;

            Player me = players.get(idx);
            me.setName(name);
            me.setJoined(true);
            me.setEliminated(false);
            me.setSpectating(false);
            room.bindToken(token, idx);
            return idx;
        } finally {
            room.lock().unlock();
        }
    }

    /** Stanza con slot vuoti (nessuno seduto), come appena creata dal repository. */
    private static GameRoom emptyRoom(int slots) {
        List<Player> players = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) players.add(new Player("Slot " + (i + 1), 3));
        return new GameRoom("TEST" + slots, new GameState(players, 3));
    }

    // ---------------------------------------------------------------
    // Runner: parte tutti insieme (latch) e attende con timeout (future).
    // ---------------------------------------------------------------

    private static void runAllTogether(List<Callable<Void>> tasks) throws Exception {
        int n = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);

            List<Future<Void>> futures = new ArrayList<>(n);
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();          // partenza sincronizzata, niente sleep
                    return task.call();
                }));
            }

            assertTrue(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "i thread non sono pronti in tempo");
            go.countDown();

            for (Future<Void> f : futures) {
                // get con timeout: se qualcosa si blocca il test fallisce invece di appendersi.
                f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
