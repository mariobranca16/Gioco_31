package it.gioco31.room;

import it.gioco31.GameConstants;
import it.gioco31.testutil.Concurrency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tetto al numero di stanze vive. Il registro è una mappa statica e le stanze
 * ne escono solo dopo ROOM_STALE_MS: senza limite, chi chiama la creazione in
 * un ciclo riempie l'heap.
 *
 * Le stanze create qui vanno tolte anche se un'asserzione fallisce a metà:
 * la mappa è condivisa da tutte le classi della stessa JVM, e lasciarne
 * migliaia dentro farebbe cadere i test successivi.
 */
class RoomRepositoryLimitTest {

    private final List<String> created = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void removeCreatedRooms() {
        for (String rid : created) RoomRepository.remove(rid);
        created.clear();
    }

    /** Riempie il registro fino a lasciare {@code freeSlots} posti liberi. */
    private void fillUpToCeiling(int freeSlots) {
        int target = GameConstants.MAX_ROOMS - freeSlots;
        while (RoomRepository.allRooms().size() < target) {
            created.add(RoomRepository.createNewRoom(2, 3).roomId());
        }
    }

    @Test
    void creationIsRefusedOnceTheCeilingIsReached() {
        fillUpToCeiling(1);

        // L'ultimo posto libero è ancora assegnabile.
        created.add(RoomRepository.createNewRoom(2, 3).roomId());

        assertThrows(RoomRepository.RoomLimitReachedException.class,
                () -> RoomRepository.createNewRoom(2, 3),
                "oltre il tetto la creazione va rifiutata, non servita");
    }

    @Test
    void freeingARoomMakesRoomForANewOne() {
        fillUpToCeiling(1);
        String last = RoomRepository.createNewRoom(2, 3).roomId();
        created.add(last);

        assertThrows(RoomRepository.RoomLimitReachedException.class,
                () -> RoomRepository.createNewRoom(2, 3));

        // Il tetto è sulle stanze vive, non sulle creazioni totali: liberato un
        // posto, la richiesta successiva passa.
        RoomRepository.remove(last);
        created.remove(last);

        created.add(RoomRepository.createNewRoom(2, 3).roomId());
    }

    @Test
    void concurrentCreatorsCannotOvershootTheCeiling() throws Exception {
        fillUpToCeiling(1);

        // Un solo posto libero e otto richieste simultanee: con un controllo
        // fuori dal lock leggerebbero tutte la stessa dimensione e sforerebbero.
        int threads = 8;
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        List<Callable<Void>> tasks = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    created.add(RoomRepository.createNewRoom(2, 3).roomId());
                    succeeded.incrementAndGet();
                } catch (RoomRepository.RoomLimitReachedException expected) {
                    refused.incrementAndGet();
                }
                return null;
            });
        }
        Concurrency.runAllTogether(tasks);

        assertEquals(1, succeeded.get(), "un solo posto libero, una sola creazione riuscita");
        assertEquals(threads - 1, refused.get(), "le altre richieste sono rifiutate");
        assertEquals(GameConstants.MAX_ROOMS, RoomRepository.allRooms().size(),
                "il registro non supera mai il tetto");
    }
}
