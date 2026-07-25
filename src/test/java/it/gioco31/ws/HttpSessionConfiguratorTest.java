package it.gioco31.ws;

import jakarta.servlet.http.HttpSession;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Il passaggio della HttpSession dall'handshake all'endpoint: avviene tramite
 * gli userProperties, che il container isola per singola connessione.
 */
class HttpSessionConfiguratorTest {

    private static <T> T proxy(Class<T> type, Map<String, Object> answers) {
        return type.cast(Proxy.newProxyInstance(
                HttpSessionConfiguratorTest.class.getClassLoader(),
                new Class<?>[]{type},
                (p, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals"   -> p == args[0];
                    case "toString" -> type.getSimpleName() + "@fake";
                    default -> answers.get(method.getName());
                }));
    }

    private static ServerEndpointConfig configWith(Map<String, Object> userProperties) {
        return proxy(ServerEndpointConfig.class, Map.of("getUserProperties", userProperties));
    }

    private static HandshakeRequest requestWith(Object httpSession) {
        Map<String, Object> answers = new HashMap<>();
        answers.put("getHttpSession", httpSession);   // può essere null
        return proxy(HandshakeRequest.class, answers);
    }

    @Test
    void handshakeExposesTheHttpSessionUnderTheAgreedKey() {
        HttpSession session = proxy(HttpSession.class, Map.of());
        Map<String, Object> props = new HashMap<>();

        new HttpSessionConfigurator()
                .modifyHandshake(configWith(props), requestWith(session), null);

        assertSame(session, props.get(HttpSessionConfigurator.HTTP_SESSION_KEY),
                "l'endpoint legge la HttpSession da questa chiave degli userProperties");
    }

    @Test
    void handshakeWithoutHttpSessionClearsAnyInheritedValue() {
        Map<String, Object> props = new HashMap<>();
        props.put(HttpSessionConfigurator.HTTP_SESSION_KEY, proxy(HttpSession.class, Map.of()));

        new HttpSessionConfigurator()
                .modifyHandshake(configWith(props), requestWith(null), null);

        assertFalse(props.containsKey(HttpSessionConfigurator.HTTP_SESSION_KEY),
                "senza HttpSession la chiave va rimossa, o l'endpoint userebbe la sessione di un altro");
    }

    /**
     * Jakarta WebSocket 2.1 §3.1.7 pretende un costruttore pubblico senza
     * argomenti su ogni endpoint annotato. Tomcat lo usa quando istanzia
     * l'endpoint dall'InstanceManager invece che dal configurator, quindi un
     * costruttore con parametri funzionerebbe solo finché resta configurato
     * un configurator personalizzato: rottura silenziosa e a runtime.
     */
    @Test
    void endpointKeepsThePublicNoArgConstructorRequiredBySpec() {
        Constructor<RoomEndpoint> ctor = assertDoesNotThrow(
                () -> RoomEndpoint.class.getConstructor(),
                "RoomEndpoint deve avere un costruttore pubblico senza argomenti");
        assertTrue(Modifier.isPublic(ctor.getModifiers()));
    }
}
