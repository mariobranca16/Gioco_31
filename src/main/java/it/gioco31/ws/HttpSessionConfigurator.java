package it.gioco31.ws;

import jakarta.servlet.http.HttpSession;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

/**
 * Rende disponibile all'endpoint la HttpSession di chi ha fatto l'handshake:
 * è lì che il servlet di join ha messo playerToken e roomId.
 */
public class HttpSessionConfigurator extends ServerEndpointConfig.Configurator {

    /** Chiave negli userProperties della sessione WebSocket. */
    public static final String HTTP_SESSION_KEY = "httpSession";

    /**
     * La config passata qui NON è quella condivisa dell'endpoint: il container
     * la avvolge in una copia per-connessione prima di invocarci (in Tomcat è
     * WsPerSessionServerEndpointConfig, che copia userProperties in una mappa
     * propria), e la stessa copia finisce poi negli userProperties della
     * WsSession. Scrivere qui è quindi già isolato per singolo handshake:
     * due connessioni simultanee non possono sovrascriversi a vicenda.
     */
    @Override
    public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {
        Object s = request.getHttpSession();
        if (s instanceof HttpSession http) {
            config.getUserProperties().put(HTTP_SESSION_KEY, http);
        } else {
            // Nessuna HttpSession: togli la chiave ereditata dalla config
            // dell'endpoint, così l'endpoint rifiuta la connessione invece di
            // lavorare su una sessione altrui.
            config.getUserProperties().remove(HTTP_SESSION_KEY);
        }
    }
}
