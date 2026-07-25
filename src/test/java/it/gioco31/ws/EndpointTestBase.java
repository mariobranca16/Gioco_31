package it.gioco31.ws;

import it.gioco31.room.GameRoom;
import it.gioco31.room.RoomRepository;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.junit.jupiter.api.AfterEach;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base dei test dell'endpoint: fabbrica stanze e sessioni finte e, soprattutto,
 * ripulisce lo stato statico condiviso anche quando un'asserzione fallisce a
 * metà test. Registro stanze e registro sessioni vivono in mappe statiche
 * condivise da tutte le classi della stessa JVM: una pulizia scritta in fondo
 * al corpo del test non verrebbe eseguita dopo un fallimento, e i residui
 * farebbero cadere test di altre classi (in particolare le pulizie del
 * repository, che spazzano l'intera mappa).
 */
abstract class EndpointTestBase {

    private final List<String> rooms = new ArrayList<>();

    /** Stanza registrata per la pulizia automatica di fine test. */
    protected GameRoom newRoom(int slots) {
        GameRoom room = RoomRepository.createNewRoom(slots, 3);
        rooms.add(room.roomId());
        return room;
    }

    /** Registra un roomId creato a mano perché venga ripulito comunque. */
    protected void trackRoom(String rid) {
        rooms.add(rid);
    }

    @AfterEach
    void cleanUpSharedStaticState() {
        for (String rid : rooms) {
            RoomEndpoint.dropRoomSessions(rid);
            RoomRepository.remove(rid);
        }
        rooms.clear();
    }

    /**
     * Sessione finta: porta il token, tiene traccia della chiusura e accetta
     * gli invii. Il remote asincrono è finto ma non nullo: con
     * {@code getAsyncRemote()} a null ogni broadcast finirebbe in NPE e
     * chiuderebbe la sessione, e non si potrebbe più distinguere una chiusura
     * voluta dal codice da un artefatto del finto.
     */
    protected static Session sessionWithToken(String token) {
        Map<String, Object> props = new HashMap<>();
        props.put("token", token);
        AtomicBoolean open = new AtomicBoolean(true);

        RemoteEndpoint.Async remote = (RemoteEndpoint.Async) Proxy.newProxyInstance(
                EndpointTestBase.class.getClassLoader(),
                new Class<?>[]{RemoteEndpoint.Async.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals"   -> proxy == args[0];
                    case "toString" -> "FakeAsyncRemote";
                    default -> null; // sendText/setSendTimeout: accettano e scartano
                });

        return (Session) Proxy.newProxyInstance(
                EndpointTestBase.class.getClassLoader(),
                new Class<?>[]{Session.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUserProperties" -> props;
                    case "getAsyncRemote"    -> remote;
                    case "isOpen"   -> open.get();
                    case "close"    -> { open.set(false); yield null; }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals"   -> proxy == args[0];
                    case "toString" -> "FakeSession[" + token + "]";
                    default -> null;
                });
    }
}
