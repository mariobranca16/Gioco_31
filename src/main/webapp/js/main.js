/* Punto d'ingresso della pagina della stanza: avvia la connessione, registra i
   listener globali e pubblica su window le funzioni che room.jsp richiama dai
   suoi onclick= inline.

   Il ponte su window serve perché un modulo ha uno scope suo: senza, gli
   onclick= della JSP non troverebbero nulla. È l'unico punto in cui questo
   codice tocca l'ambiente globale, ed è anche l'unica cosa da togliere il
   giorno in cui i 12 onclick= diventeranno addEventListener. */

import { connect, heartbeatTick, retryNow, sendAction } from "./net.js";
import { setPendingTopVisible, tickTurnTimer } from "./render.js";
import { closeReveal, copyInvite, inviteUrl } from "./ui.js";
import { ackNotice, confirmKeep, pileClick, restartGame } from "./actions.js";

Object.assign(window, {
    ackNotice,
    closeReveal,
    confirmKeep,
    copyInvite,
    pileClick,
    restartGame,
    retryNow,
    sendAction
});

document.addEventListener("DOMContentLoaded", () => {
    const inp = document.getElementById("inviteLink");
    if (inp) inp.value = inviteUrl();

    setPendingTopVisible(false, null);
    setInterval(tickTurnTimer, 1000);
    connect();
});

/* Riconnessione reattiva: i timer in background su mobile vengono sospesi, così
   il setTimeout del backoff può non scattare. Quando il tab torna visibile o la
   rete ritorna, riproviamo subito (connect() ignora le chiamate ridondanti).

   Senza azzerare reconnectAttempts: il contatore misura i tentativi falliti di
   fila e si azzera da solo alla prima connessione riuscita. Resettarlo qui
   terrebbe il backoff inchiodato al minimo — su un telefono che riaccende lo
   schermo ogni pochi secondi con la rete giù significa martellare il server —
   e impedirebbe per sempre la comparsa del pulsante "Riprova", che scatta
   proprio dopo RECONNECT_RETRY_ATTEMPTS tentativi a vuoto.

   Il controllo di liveness va fatto prima: un socket rimasto OPEN mentre il tab
   era in background è spesso proprio quello morto in silenzio, e connect() da
   solo lo darebbe per buono senza fare nulla. */
document.addEventListener("visibilitychange", () => {
    if (document.visibilityState !== "visible") return;
    heartbeatTick();
    connect();
});
window.addEventListener("online", () => { heartbeatTick(); connect(); });

/* Nessuna uscita automatica su pagehide: l'evento scatta anche su navigazioni
   interne (l'avvio partita è una POST verso /start, un reload, il cambio app),
   e liberare il posto in quei casi caccerebbe l'host proprio mentre avvia. La
   liberazione dello slot è già garantita dalla chiusura del WebSocket, che fa
   partire la grazia lato server (breve per i normali, lunga per l'host). */
