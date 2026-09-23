/* Identità della stanza (iniettata dalla JSP nel tag <script> inline) e tempi
   dell'interfaccia. Sono gli unici valori davvero globali della pagina. */

export const ROOM_ID = window.__ROOM_ID__;
export const CTX = window.__CTX__;

/* Tempi dell'interfaccia (ms) */
export const REVEAL_AUTO_CLOSE_MS = 12000;   // l'esito del round si chiude da solo
export const RECENT_RESULT_MS = 15000;       // esito ancora "fresco" dopo un reload
export const TOAST_MS = 1600;
export const RECONNECT_BASE_DELAY_MS = 600;  // backoff esponenziale della riconnessione
export const RECONNECT_MAX_DELAY_MS = 5000;
export const RECONNECT_RETRY_ATTEMPTS = 8;   // dopo tanti tentativi mostra il pulsante "Riprova"
export const HEARTBEAT_MS = 20000;           // ping periodico: tiene vivo il socket dietro proxy/nginx
export const LIVENESS_CHECK_MS = 5000;       // ogni quanto si controlla se il socket dà ancora segni di vita
export const LIVENESS_TIMEOUT_MS = 2 * HEARTBEAT_MS; // due ping senza risposta: connessione da buttare
export const FX_THROTTLE_MS = 250;           // anti-doppione per le animazioni di pesca
