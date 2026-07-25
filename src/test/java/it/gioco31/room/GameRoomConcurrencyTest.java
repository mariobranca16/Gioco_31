package it.gioco31.room;

import it.gioco31.GameConstants;
import it.gioco31.model.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;

import static it.gioco31.testutil.Concurrency.runAllTogether;
import static it.gioco31.testutil.Fixtures.newRoom;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Corse reali sul ciclo di vita di una GameRoom.
 *
 * <p>I thread chiamano i metodi della stanza <b>senza</b> prendere
 * {@code room.lock()} dall'esterno: il lock lo prende il codice di produzione,
 * quindi l'interleaving avviene dove avviene davvero. Un test che si prende il
 * lock attorno a ogni operazione serializza sé stesso e passerebbe identico
 * eseguito su un thread solo — non può rilevare nessuna corsa.
 */
class GameRoomConcurrencyTest {

    private static final long GRACE = GameConstants.DISCONNECT_GRACE_MS;

    private static List<String> tokens(int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add("t" + i);
        return out;
    }

    /**
     * Più sweeper girano insieme sugli stessi token scaduti: la decisione e la
     * rimozione stanno sotto lo stesso lock, quindi ogni token esce una volta
     * sola. Senza la ri-verifica della scadenza dentro il lock, due sweeper
     * rimuoverebbero (e segnalerebbero) lo stesso giocatore.
     */
    @Test
    void everyExpiredTokenIsRemovedByExactlyOneSweeper() throws Exception {
        final int slots = 6;
        final int sweepers = 8;

        GameRoom room = newRoom(slots);
        room.engine().startRound(room.state()); // fuori dalla lobby: nessuna grazia speciale per l'host

        long now = System.currentTimeMillis();
        for (String tok : tokens(slots)) room.markDisconnected(tok, now - GRACE - 1);

        Queue<String> removed = new ConcurrentLinkedQueue<>();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < sweepers; t++) {
            tasks.add(() -> {
                removed.addAll(room.sweepDisconnected(now));
                return null;
            });
        }

        runAllTogether(tasks);

        List<String> reported = new ArrayList<>(removed);
        assertEquals(reported.size(), new HashSet<>(reported).size(),
                "un token è stato rimosso (e segnalato) più di una volta: " + reported);
        assertEquals(new HashSet<>(tokens(slots)), new HashSet<>(reported),
                "tutti i token scaduti devono uscire, una volta ciascuno");

        for (String tok : reported) {
            assertNull(room.indexByToken(tok), "il token rimosso non deve restare legato: " + tok);
        }
        assertFalse(room.hasAnyJoinedPlayers(), "liberati tutti gli slot");
    }

    /**
     * Riconnessioni e sweep in corsa fra loro. Chi vince è indifferente: quello
     * che non deve mai succedere è che la stanza resti in uno stato incoerente,
     * cioè un token contemporaneamente rimosso e ancora legato a uno slot (o
     * né l'uno né l'altro).
     */
    @Test
    void reconnectingWhileSweepersRunNeverLeavesTheRoomInconsistent() throws Exception {
        final int slots = 6;
        final int sweepers = 6;
        final int reconnectors = 6;
        final int rounds = 300;

        GameRoom room = newRoom(slots);
        room.engine().startRound(room.state());

        long now = System.currentTimeMillis();
        List<String> all = tokens(slots);
        for (String tok : all) room.markDisconnected(tok, now - GRACE - 1);

        Queue<String> removed = new ConcurrentLinkedQueue<>();
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int t = 0; t < sweepers; t++) {
            tasks.add(() -> {
                for (int i = 0; i < rounds; i++) removed.addAll(room.sweepDisconnected(now));
                return null;
            });
        }
        for (int t = 0; t < reconnectors; t++) {
            final int offset = t;
            tasks.add(() -> {
                for (int i = 0; i < rounds; i++) room.markConnected(all.get((i + offset) % slots));
                return null;
            });
        }

        runAllTogether(tasks);

        List<String> reported = new ArrayList<>(removed);
        assertEquals(reported.size(), new HashSet<>(reported).size(),
                "un token è stato rimosso più di una volta: " + reported);

        Set<String> removedSet = new HashSet<>(reported);
        for (String tok : all) {
            boolean isRemoved = removedSet.contains(tok);
            boolean stillBound = room.indexByToken(tok) != null;
            assertNotEquals(isRemoved, stillBound,
                    "il token " + tok + " dev'essere o rimosso o ancora legato, mai entrambi né nessuno dei due");
        }
    }

    /**
     * L'host disconnesso in lobby cede il ruolo a chi è rimasto connesso. Con
     * più sweeper in parallelo la consegna deve avvenire una volta sola e
     * lasciare la stanza con esattamente un host fra i presenti.
     */
    @Test
    void theHostRoleIsHandedOverOnceUnderConcurrentSweeps() throws Exception {
        final int slots = 6;
        final int sweepers = 8;

        GameRoom room = newRoom(slots); // fase WAITING_FOR_PLAYERS, host = t0
        List<String> all = tokens(slots);

        // t1..t5 connessi (candidati alla successione), t0 disconnesso e scaduto.
        for (int i = 1; i < slots; i++) room.markConnected(all.get(i));
        long now = System.currentTimeMillis();
        room.markDisconnected("t0", now - GRACE - 1);
        assertTrue(room.isHost("t0"));

        Queue<String> removed = new ConcurrentLinkedQueue<>();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < sweepers; t++) {
            tasks.add(() -> {
                removed.addAll(room.sweepDisconnected(now));
                return null;
            });
        }

        runAllTogether(tasks);

        assertEquals(List.of("t0"), new ArrayList<>(removed),
                "esce solo l'host scaduto, e una volta sola");
        assertFalse(room.isHost("t0"), "chi è uscito non può restare host");

        List<String> stillIn = all.stream().filter(tok -> room.indexByToken(tok) != null).toList();
        assertEquals(slots - 1, stillIn.size());
        assertEquals(1, stillIn.stream().filter(room::isHost).count(),
                "fra i presenti deve esserci esattamente un host");
        assertTrue(room.isHost("t1"), "il ruolo passa al connesso con l'indice più basso");
    }

    /**
     * bumpSeq() è un {@code ++} su un long non volatile: il contratto è che si
     * invochi solo col lock della stanza (lo fa broadcast()). Qui i thread lo
     * rispettano — il punto non è l'interleaving ma verificare che sotto
     * contesa non si perda nemmeno un incremento e non escano due volte lo
     * stesso numero, cioè che i client non possano vedere due stati diversi
     * con la stessa versione.
     */
    @Test
    void concurrentBumpsLoseNoIncrementAndNeverRepeatANumber() throws Exception {
        final int threads = 8;
        final int perThread = 2000;
        final int total = threads * perThread;

        GameRoom room = newRoom(4);
        Queue<Long> seqs = new ConcurrentLinkedQueue<>();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                for (int i = 0; i < perThread; i++) {
                    room.lock().lock();
                    try {
                        seqs.add(room.state().bumpSeq()); // come dentro broadcast()
                    } finally {
                        room.lock().unlock();
                    }
                }
                return null;
            });
        }

        runAllTogether(tasks);

        assertEquals(total, seqs.size());
        assertEquals(total, new HashSet<>(seqs).size(), "due broadcast non possono condividere la stessa stateSeq");
        assertEquals(total, Collections.max(seqs), "nessun incremento perso: l'ultima sequenza vale N");
        assertEquals(total, room.state().getStateSeq());
    }

    /**
     * Un giocatore che esce mentre gli altri escono a loro volta: la stanza non
     * deve mai risultare con più giocatori seduti di quanti token siano legati.
     */
    @Test
    void concurrentLeavesKeepSeatsAndTokensAligned() throws Exception {
        final int slots = 6;

        GameRoom room = newRoom(slots);
        List<String> all = tokens(slots);
        for (String tok : all) room.markConnected(tok);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (String tok : all) {
            tasks.add(() -> {
                room.releaseTokenAndFreeSlot(tok);
                return null;
            });
        }

        runAllTogether(tasks);

        for (String tok : all) assertNull(room.indexByToken(tok));
        long seated = room.state().getPlayers().stream().filter(Player::isJoined).count();
        assertEquals(0, seated, "usciti tutti, nessuno deve restare seduto");
        assertFalse(room.isHost("t0"));
    }
}
