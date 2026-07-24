package it.gioco31.ws;

import jakarta.servlet.http.HttpSession;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

public class HttpSessionConfigurator extends ServerEndpointConfig.Configurator {

    // In Tomcat il ServerEndpointConfig (e la sua mappa userProperties) è
    // condiviso tra tutte le connessioni dell'endpoint, e viene copiato nella
    // WsSession solo dopo modifyHandshake: due handshake simultanei si
    // sovrascriverebbero a vicenda in quella finestra, assegnando a un giocatore
    // la HttpSession (e quindi il posto) di un altro. Tomcat però invoca
    // modifyHandshake e getEndpointInstance sullo stesso thread durante
    // l'upgrade, quindi passiamo la HttpSession via ThreadLocal: è per-connessione.
    private static final ThreadLocal<HttpSession> HTTP_SESSION = new ThreadLocal<>();

    @Override
    public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {
        Object s = request.getHttpSession();
        if (s instanceof HttpSession http) {
            HTTP_SESSION.set(http);
        } else {
            // Nessuna HttpSession: azzera per non ereditare un valore stantio
            // lasciato da un handshake precedente sullo stesso thread.
            HTTP_SESSION.remove();
        }
    }

    @Override
    public <T> T getEndpointInstance(Class<T> clazz) throws InstantiationException {
        try {
            if (clazz == RoomEndpoint.class) {
                return clazz.cast(new RoomEndpoint(HTTP_SESSION.get()));
            }
            return super.getEndpointInstance(clazz);
        } finally {
            // Rimozione sempre, anche sul percorso di errore: altrimenti la
            // HttpSession resta appesa a un thread del pool e la prossima
            // connessione servita da quel thread ne erediterebbe una sbagliata.
            HTTP_SESSION.remove();
        }
    }
}
