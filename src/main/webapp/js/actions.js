/* Le mosse dell'utente: leggono lo stato per capire se sono lecite, mandano
   l'azione al server e aggiornano subito la parte di interfaccia che non ha
   senso lasciare indietro fino alla risposta. */

import { sendAction } from "./net.js";
import { currentState } from "./state.js";
import { isInPlay } from "./util.js";
import { clearHandSelection, selectedDiscard, setPendingTopVisible } from "./render.js";
import { clearNotice, currentNoticeId } from "./ui.js";

export function restartGame(){
    sendAction("restartGame");
}

/* Il click sui mucchi rifà i controlli che il render usa per abilitare i
   pulsanti: al mucchio si può cliccare anche fuori dal pulsante, e il server
   respingerebbe comunque, ma con un giro inutile e un avviso a video. */
export function pileClick(which){
    const lastState = currentState();
    if (!lastState) return;

    const phase = lastState.phase ?? "";
    const viewerIndex = lastState.viewerIndex ?? -1;
    const currentIndex = lastState.currentIndex ?? -1;

    const players = Array.isArray(lastState.players) ? lastState.players : [];
    const viewerObj = (viewerIndex >= 0 && viewerIndex < players.length) ? players[viewerIndex] : null;

    if (viewerObj?.eliminated) return;

    const myTurn = (viewerIndex === currentIndex);
    const inPlay = isInPlay(phase);
    const hasPending = !!(viewerObj && viewerObj.pendingDraw);

    if (!(myTurn && inPlay && !hasPending)) return;

    if (which === "deck") sendAction("drawDeck");
    if (which === "discard" && !!lastState.discardTop) sendAction("drawDiscard");
}

export function confirmKeep(){
    const idx = selectedDiscard();
    if (idx == null) return;

    sendAction("keep", idx);

    // reset UI immediato (poi arriva lo stato dal server)
    clearHandSelection();
    setPendingTopVisible(false, null);
}

export function ackNotice(){
    const id = currentNoticeId();
    if (id != null) sendAction("ackNotice", id);
    clearNotice();

    // Toglie l'avviso anche dallo stato in memoria: senza questo un render
    // successivo, prima che arrivi lo stato aggiornato, lo rimostrerebbe.
    try {
        const lastState = currentState();
        const vi = lastState?.viewerIndex;
        if (vi != null && lastState?.players?.[vi]) {
            lastState.players[vi].noticeId = null;
            lastState.players[vi].noticeMsg = null;
        }
    } catch (e) {}
}
