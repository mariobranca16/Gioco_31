package it.gioco31.ws;

import it.gioco31.room.GameRoom;
import it.gioco31.room.RoomRepository;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.SendHandler;
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

    /**
     * Arma un avviso per il giocatore e restituisce il messaggio che lo
     * conferma: un'azione che cambia davvero lo stato, quindi ritrasmessa.
     * Da quando il broadcast è condizionato all'effettiva modifica, un
     * {@code ackNotice} con un id che non corrisponde a nessun avviso è un
     * no-op e non incrementa stateSeq: non va più bene come "azione qualsiasi".
     */
    protected static String armedAckNotice(GameRoom room, int playerIndex) {
        room.state().setNoticeForPlayer(playerIndex, "avviso di prova");
        long id = room.state().getNoticeForPlayer(playerIndex).getId();
        return action("ackNotice", id);
    }

    /** Messaggio del client nel formato del protocollo: JSON con azione e argomento. */
    protected static String action(String name) {
        return "{\"action\":\"" + name + "\"}";
    }

    protected static String action(String name, long arg) {
        return "{\"action\":\"" + name + "\",\"arg\":" + arg + "}";
    }

    @AfterEach
    void cleanUpSharedStaticState() {
        for (String rid : rooms) {
            RoomEndpoint.dropRoomSessions(rid);
            RoomRepository.remove(rid);
        }
        rooms.clear();
    }

    /** Un invio partito e non ancora concluso, con il suo callback. */
    protected record Sent(String json, SendHandler handler) {}

    /**
     * Sessione finta il cui remote <b>non</b> completa gli invii da solo: è il
     * test a decidere quando ciascuno finisce, così si può osservare la
     * finestra in cui il remote vero è ancora in scrittura (e rifiuterebbe un
     * secondo sendText).
     */
    protected static Session recordingSession(String token, List<Sent> sent) {
        Map<String, Object> props = new HashMap<>();
        props.put("token", token);
        AtomicBoolean open = new AtomicBoolean(true);

        RemoteEndpoint.Async remote = (RemoteEndpoint.Async) Proxy.newProxyInstance(
                EndpointTestBase.class.getClassLoader(),
                new Class<?>[]{RemoteEndpoint.Async.class},
                (proxy, method, args) -> {
                    if ("sendText".equals(method.getName()) && args != null && args.length == 2) {
                        sent.add(new Sent((String) args[0], (SendHandler) args[1]));
                        return null;
                    }
                    return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals"   -> proxy == args[0];
                        case "toString" -> "RecordingAsyncRemote";
                        default -> null;
                    };
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
                    case "toString" -> "RecordingSession[" + token + "]";
                    default -> null;
                });
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
