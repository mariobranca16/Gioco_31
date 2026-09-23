/* Elementi di contorno: toast, banner di riconnessione, i tre overlay
   (esito del round, avviso, vincitore) e il link d'invito. Nessuno di questi
   parla col server: chi deve mandare un'azione sta in actions.js o net.js. */

import { CTX, RECONNECT_RETRY_ATTEMPTS, REVEAL_AUTO_CLOSE_MS, ROOM_ID, TOAST_MS } from "./config.js";
import { cardImageUrl, escapeHtml } from "./util.js";

export function toast(msg){
    const t = document.getElementById("toast");
    if (!t) return;
    t.textContent = msg ?? "";
    t.classList.add("on");
    clearTimeout(toast._tid);
    toast._tid = setTimeout(() => t.classList.remove("on"), TOAST_MS);
}

/* Banner persistente (non un toast a ogni tentativo): resta finché il socket
   non si riapre, così l'utente vede lo stato senza spam. Il numero di tentativi
   arriva da net.js, che è chi lo conta. */
export function showReconnectBanner(attempts){
    const b = document.getElementById("reconnectBanner");
    if (b) b.classList.add("on");
    // Il pulsante "Riprova" compare solo dopo parecchi tentativi a vuoto.
    const retry = document.getElementById("reconnectRetry");
    if (retry) retry.style.display = (attempts >= RECONNECT_RETRY_ATTEMPTS) ? "" : "none";
}

export function hideReconnectBanner(){
    const b = document.getElementById("reconnectBanner");
    if (b) b.classList.remove("on");
    const retry = document.getElementById("reconnectRetry");
    if (retry) retry.style.display = "none";
}

export function hideRetryButton(){
    const retry = document.getElementById("reconnectRetry");
    if (retry) retry.style.display = "none";
}

/* =========================
   Fine round — mani rivelate
   ========================= */

let revealTimer = null;

export function showReveal(rr){
    const ov = document.getElementById("revealOverlay");
    const msg = document.getElementById("revealMsg");
    const hands = document.getElementById("revealHands");
    if (!ov || !hands) return;

    if (msg) msg.textContent = rr.message ?? "";
    hands.innerHTML = "";

    (rr.hands ?? []).forEach(h => {
        const div = document.createElement("div");
        div.className = "revealHand" + (h.lostLife ? " lost" : "");

        const cardsHtml = (h.cards ?? []).map(c => {
            const url = cardImageUrl(c);
            const labelEsc = escapeHtml(c?.label ?? "");
            return url ? `<img src="${url}" alt="${labelEsc}">` : `<span>${labelEsc}</span>`;
        }).join("");

        let badge = "";
        if (h.eliminated) badge = '<span class="revealBadge out">eliminato</span>';
        else if (h.lostLife) badge = '<span class="revealBadge">-1 vita</span>';

        div.innerHTML = `
          <div class="revealName">${escapeHtml(h.name ?? "—")}<span class="revealScore">${Number(h.score ?? 0)}</span>${badge}</div>
          <div class="revealCards">${cardsHtml}</div>`;
        hands.appendChild(div);
    });

    ov.style.display = "flex";
    clearTimeout(revealTimer);
    revealTimer = setTimeout(closeReveal, REVEAL_AUTO_CLOSE_MS);
}

export function closeReveal(){
    clearTimeout(revealTimer);
    const ov = document.getElementById("revealOverlay");
    if (ov) ov.style.display = "none";
}

/* =========================
   Avviso + vincitore
   ========================= */

/* Quale avviso è a schermo: serve a non riaprire quello già mostrato a ogni
   stato, e a dire al server quale l'utente ha confermato. */
let shownNoticeId = null;

export function currentNoticeId(){
    return shownNoticeId;
}

export function showNotice(id, msg){
    shownNoticeId = id;
    const m = document.getElementById("noticeMsg");
    const ov = document.getElementById("noticeOverlay");
    if (m) m.textContent = msg ?? "—";
    if (ov) ov.style.display = "flex";
}

function hideNotice(){
    const ov = document.getElementById("noticeOverlay");
    if (ov) ov.style.display = "none";
}

/** Chiude l'avviso e dimentica quale fosse: il prossimo va rimostrato. */
export function clearNotice(){
    shownNoticeId = null;
    hideNotice();
}

export function showWinnerOverlay(name, isHost){
    const ov = document.getElementById("winnerOverlay");
    const nm = document.getElementById("winnerName");
    const sub = document.getElementById("winnerSub");
    const btn = document.getElementById("btnRestart");

    if (nm) nm.textContent = name ?? "—";
    if (sub) {
        sub.textContent = isHost
            ? "Puoi avviare un'altra partita per tutti."
            : "In attesa che il creatore della stanza avvii un'altra partita.";
    }
    if (btn) btn.disabled = !isHost;
    if (ov) ov.style.display = "flex";
}

export function hideWinnerOverlay(){
    const ov = document.getElementById("winnerOverlay");
    if (ov) ov.style.display = "none";
}

/* =========================
   Invito
   ========================= */

export function inviteUrl(){
    return `${location.origin}${CTX}/join?room=${encodeURIComponent(ROOM_ID)}`;
}

function fallbackCopy(){
    const inp = document.getElementById("inviteLink");
    if (!inp) return;
    inp.focus();
    inp.select();
    try { document.execCommand("copy"); } catch (e) {}
}

export function copyInvite(){
    const url = inviteUrl();
    const inp = document.getElementById("inviteLink");
    if (inp) inp.value = url;

    if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(url)
            .then(() => toast("Link copiato"))
            .catch(() => { fallbackCopy(); toast("Link copiato"); });
    } else {
        fallbackCopy();
        toast("Link copiato");
    }
}
