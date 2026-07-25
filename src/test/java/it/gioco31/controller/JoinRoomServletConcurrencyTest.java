package it.gioco31.controller;

import it.gioco31.model.Player;
import it.gioco31.room.GameRoom;
import it.gioco31.room.RoomRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;

import static it.gioco31.testutil.Concurrency.runAllTogether;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Ingressi simultanei nella stessa stanza, sul codice vero di
 * {@link JoinRoomServlet#tryJoin}: controllo del nome, ricerca dello slot
 * libero e binding del token devono essere un'unica operazione atomica,
 * altrimenti due richieste possono prendersi lo stesso posto.
 */
class JoinRoomServletConcurrencyTest {

    private final List<String> createdRooms = new ArrayList<>();

    private GameRoom newRoom(int slots) {
        GameRoom room = RoomRepository.createNewRoom(slots, 3);
        createdRooms.add(room.roomId());
        return room;
    }

    @AfterEach
    void removeCreatedRooms() {
        for (String rid : createdRooms) RoomRepository.remove(rid);
        createdRooms.clear();
    }

    @Test
    void concurrentJoinsFillEachSlotExactlyOnce() throws Exception {
        final int slots = 4;
        final int candidates = 20;

        GameRoom room = newRoom(slots);

        Queue<Integer> assignedIdx = new ConcurrentLinkedQueue<>();
        Queue<String> errors = new ConcurrentLinkedQueue<>();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < candidates; t++) {
            final String name = "Player" + t;   // tutti distinti e validi
            final String token = UUID.randomUUID().toString();
            tasks.add(() -> {
                String error = JoinRoomServlet.tryJoin(room, name, token);
                if (error == null) {
                    Integer idx = room.indexByToken(token);
                    assertNotNull(idx, "chi entra deve avere il token legato a uno slot");
                    assignedIdx.add(idx);
                } else {
                    errors.add(error);
                }
                return null;
            });
        }

        runAllTogether(tasks);

        List<Integer> assigned = new ArrayList<>(assignedIdx);
        assertEquals(slots, assigned.size(), "devono riuscire esattamente " + slots + " ingressi");
        assertEquals(Set.of(0, 1, 2, 3), new HashSet<>(assigned),
                "occupati tutti e soli gli slot, senza che due giocatori prendano lo stesso posto");
        assertEquals(candidates - slots, errors.size());
        assertTrue(errors.stream().allMatch(e -> e.contains("piena")),
                "chi non entra deve trovare la stanza piena, non altri errori: " + new HashSet<>(errors));

        long seated = room.state().getPlayers().stream().filter(Player::isJoined).count();
        assertEquals(slots, seated, "in stanza risultano seduti esattamente " + slots + " giocatori");
    }

    @Test
    void theSameNameCanBeTakenByOnlyOneOfTheConcurrentRequests() throws Exception {
        final int slots = 6;
        final int candidates = 12;

        GameRoom room = newRoom(slots);

        Queue<String> accepted = new ConcurrentLinkedQueue<>();
        Queue<String> rejected = new ConcurrentLinkedQueue<>();

        // Tutti chiedono lo stesso nome: deve passarne uno solo, gli altri
        // devono ricevere l'errore di nome già in uso (non un posto occupato).
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < candidates; t++) {
            final String token = UUID.randomUUID().toString();
            tasks.add(() -> {
                String error = JoinRoomServlet.tryJoin(room, "Ambrogio", token);
                if (error == null) accepted.add(token);
                else rejected.add(error);
                return null;
            });
        }

        runAllTogether(tasks);

        assertEquals(1, accepted.size(), "un nome può essere occupato da un solo giocatore");
        assertEquals(candidates - 1, rejected.size());
        assertTrue(rejected.stream().allMatch(e -> e.contains("Nome")),
                "gli altri devono fallire sul nome duplicato: " + new HashSet<>(rejected));

        long seated = room.state().getPlayers().stream().filter(Player::isJoined).count();
        assertEquals(1, seated, "un solo slot occupato: i tentativi respinti non devono consumarne");
    }
}
