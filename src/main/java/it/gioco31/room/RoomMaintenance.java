package it.gioco31.room;

import it.gioco31.ws.RoomEndpoint;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manutenzione periodica delle stanze: rimuove i giocatori disconnessi
 * oltre il periodo di grazia (sbloccando la partita) e ripulisce le
 * stanze stantie anche in assenza di traffico HTTP.
 */
@WebListener
public final class RoomMaintenance implements ServletContextListener {

    private static final Logger LOG = Logger.getLogger(RoomMaintenance.class.getName());
    private static final long SWEEP_INTERVAL_MS = 5_000L;

    private ScheduledExecutorService executor;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gioco31-room-maintenance");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(RoomMaintenance::sweep,
                SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (executor != null) executor.shutdownNow();
    }

    private static void sweep() {
        try {
            long now = System.currentTimeMillis();
            for (GameRoom room : RoomRepository.allRooms()) {
                // Catch per stanza: una stanza corrotta non deve fermare
                // la manutenzione di tutte le altre.
                try {
                    List<String> expired = room.sweepDisconnected(now);
                    if (!expired.isEmpty()) {
                        LOG.info(() -> "Room " + room.roomId() + ": rimossi "
                                + expired.size() + " giocatore/i disconnesso/i");
                    }

                    boolean turnForced = room.sweepTurnTimeout(now);
                    if (turnForced) {
                        LOG.info(() -> "Room " + room.roomId() + ": turno scaduto giocato d'ufficio");
                    }

                    if (!expired.isEmpty() || turnForced) {
                        RoomEndpoint.broadcastRoom(room.roomId());
                    }
                } catch (RuntimeException ex) {
                    LOG.log(Level.WARNING, ex,
                            () -> "Manutenzione fallita per la room " + room.roomId());
                }
            }
            // Le stanze rimosse perché stantie lasciano entry appese nel
            // registro sessioni dell'endpoint: le ripuliamo qui, dove
            // conosciamo sia il repository sia l'endpoint (nessuna dipendenza
            // circolare tra i due).
            for (String rid : RoomRepository.cleanupStaleRoomsNow()) {
                RoomEndpoint.dropRoomSessions(rid);
            }
        } catch (Throwable t) {
            // scheduleWithFixedDelay cancella il task per sempre se un
            // Throwable sfugge: da qui non deve mai uscire nulla.
            LOG.log(Level.SEVERE, "Manutenzione stanze fallita", t);
        }
    }
}
