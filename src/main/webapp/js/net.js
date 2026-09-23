/* Il WebSocket: connessione, riconnessione, heartbeat e invio delle azioni.
   È l'unico modulo che tocca il socket, e il solo che sa quanti tentativi di
   riconnessione sono già andati a vuoto. */

import {
    CTX,
    HEARTBEAT_MS,
    LIVENESS_CHECK_MS,
    LIVENESS_TIMEOUT_MS,
    RECONNECT_BASE_DELAY_MS,
    RECONNECT_MAX_DELAY_MS,
    ROOM_ID
} from "./config.js";
import { acceptState, resetStateSeq } from "./state.js";
import { render } from "./render.js";
import { hideReconnectBanner, hideRetryButton, showReconnectBanner, toast } from "./ui.js";

let ws = null;
let reconnectAttempts = 0;
let reconnectTimer = null;
let heartbeatTimer = null;
let lastFrameAt = 0;   // ultimo frame ricevuto dal server, di qualsiasi tipo
let lastPingAt = 0;

/* I testi di chiusura sono contratto col server: RoomEndpoint li usa per
   distinguere le chiusure definitive da quelle riconnettibili. Non modificarli
   senza aggiornare anche il metodo close() lato Java. */
const FATAL_CLOSE_REASONS = [
    "Room not found",
    "Not joined",
    "No HTTP session",
    "Invalid player"
];

function wsUrl(roomId){
    const proto = (location.protocol === "https:") ? "wss" : "ws";
    return `${proto}://${location.host}${CTX}/ws/${encodeURIComponent(roomId)}`;
}

export function connect(){
    // Solo un socket vivo (aperto o in apertura) blocca un nuovo tentativo: uno
    // in CLOSING non consegnerà più nulla e non tornerà mai aperto, quindi va
    // superato invece che atteso (aspettarlo significherebbe non riconnettersi
    // mai più se il browser non recapitasse il suo close).
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;

    clearTimeout(reconnectTimer);
    reconnectTimer = null;

    // Gli handler si chiudono sul socket a cui appartengono (sock) e non sulla
    // variabile di modulo ws: un socket già sostituito può recapitare il suo
    // close/error in ritardo, e senza questa guardia spegnerebbe l'heartbeat
    // della connessione nuova, mostrerebbe il banner su una connessione sana o
    // — su chiusura fatale — butterebbe l'utente fuori da una sessione valida.
    const sock = new WebSocket(wsUrl(ROOM_ID));
    ws = sock;

    sock.onopen = () => {
        if (sock !== ws) return;
        reconnectAttempts = 0;
        resetStateSeq();
        hideReconnectBanner();
        startHeartbeat();
    };

    sock.onmessage = (ev) => {
        if (sock !== ws) return;
        // Qualunque frame vale come segno di vita, anche quello che scartiamo.
        lastFrameAt = Date.now();
        try {
            const state = JSON.parse(ev.data);
            // Risposta all'heartbeat: non è uno stato, non va renderizzata.
            if (state && state.pong) return;
            if (!acceptState(state)) return;
            render();
        } catch (e) {
            console.error("Bad JSON", e);
        }
    };

    sock.onclose = (ev) => {
        // Socket superato: la connessione corrente è un'altra, non toccarla.
        if (sock !== ws) return;
        stopHeartbeat();

        const reason = String(ev.reason || "");
        const fatal = FATAL_CLOSE_REASONS.some(x => reason.includes(x));

        if (fatal) {
            hideReconnectBanner();
            console.warn("WS closed (fatal):", ev.code, reason);
            toast("Sei stato rimosso dalla stanza");
            setTimeout(() => {
                location.href = `${CTX}/join?room=${encodeURIComponent(ROOM_ID)}`;
            }, 1500);
            return;
        }

        const delay = Math.min(RECONNECT_MAX_DELAY_MS,
            RECONNECT_BASE_DELAY_MS * (2 ** Math.min(reconnectAttempts, 4)));
        reconnectAttempts++;

        console.warn("WS closed. Reconnect in", delay, "ms", ev.code, reason);
        showReconnectBanner(reconnectAttempts);
        reconnectTimer = setTimeout(connect, delay);
    };

    sock.onerror = () => {
        // Chiude il socket che ha davvero fallito, non quello corrente.
        try { sock.close(); } catch (e) {}
    };
}

/* Heartbeat: un ping ogni HEARTBEAT_MS tiene aperta la connessione anche in
   lobby, dove non passa altro traffico, evitando la chiusura per inattività
   di proxy/nginx. Il server risponde con un pong, ed è quella risposta a dire
   che il socket è ancora vivo. Attivo solo a socket aperto. */
function startHeartbeat(){
    stopHeartbeat();
    lastFrameAt = Date.now();
    lastPingAt = Date.now();
    heartbeatTimer = setInterval(heartbeatTick, LIVENESS_CHECK_MS);
}

function stopHeartbeat(){
    if (heartbeatTimer != null) {
        clearInterval(heartbeatTimer);
        heartbeatTimer = null;
    }
}

/* Manda i ping e, soprattutto, controlla che qualcosa torni indietro.
   Su un cambio di rete (mobile che passa da wifi a dati) la connessione TCP
   muore in silenzio: il socket resta OPEN, onclose non scatta mai e il tavolo
   si congela mentre il server, passata la grazia, espelle il giocatore. Il
   solo modo per accorgersene dal browser è misurare da quanto non arriva un
   frame. */
export function heartbeatTick(){
    if (!ws || ws.readyState !== WebSocket.OPEN) return;

    const now = Date.now();
    if (now - lastFrameAt > LIVENESS_TIMEOUT_MS) {
        dropDeadSocket(now - lastFrameAt);
        return;
    }

    if (now - lastPingAt >= HEARTBEAT_MS) {
        lastPingAt = now;
        sendAction("ping");
    }
}

/* Butta via un socket che non risponde più e riapre subito. Non aspettiamo il
   suo onclose: è proprio l'evento che su una rete morta non arriva (o arriva
   minuti dopo). Il socket viene abbandonato, e il suo close tardivo sarà
   ignorato dalle guardie sull'identità (sock !== ws). */
function dropDeadSocket(silentForMs){
    const dead = ws;
    stopHeartbeat();
    console.warn("WS muto da", silentForMs, "ms: lo abbandono e riconnetto");

    ws = null;
    try { dead.close(); } catch (e) {}

    showReconnectBanner(reconnectAttempts);
    connect();
}

export function retryNow(){
    reconnectAttempts = 0;
    hideRetryButton();

    // Il pulsante compare quando la rete è messa male, e lì il socket resta
    // spesso piantato in CONNECTING per decine di secondi (portale captive,
    // rete morta): connect() si rifiuterebbe di partire e il pulsante
    // sembrerebbe rotto. Lo abbandoniamo esplicitamente — passa in CLOSING e
    // il suo close tardivo verrà ignorato dalle guardie sull'identità.
    if (ws && ws.readyState === WebSocket.CONNECTING) {
        try { ws.close(); } catch (e) {}
    }
    connect();
}

/* Le azioni viaggiano come JSON, nello stesso formato in cui torna lo stato:
   { "action": "keep", "arg": 2 }. L'argomento, quando c'è, è un numero. */
export function sendAction(action, arg){
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    ws.send(JSON.stringify(arg === undefined ? { action } : { action, arg }));
}
