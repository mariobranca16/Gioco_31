/* Scrive a schermo la vista calcolata da view.js. Qui non si decide niente:
   ogni funzione prende un pezzo di vista già pronto e lo applica al DOM.
   L'unica cosa che resta di competenza di questo modulo è il confronto con
   *com'era prima* — animazioni di pesca, cambio di turno, esito di round già
   mostrato — perché richiede una memoria che la vista, essendo pura, non ha. */

import { RECENT_RESULT_MS } from "./config.js";
import { currentState } from "./state.js";
import { cardImageUrl, cardImgHtml, cardKey, escapeHtml, hearts } from "./util.js";
import { computeView } from "./view.js";
import {
    shouldAnimateDeckDraw,
    shouldAnimateDiscardDraw,
    triggerDeckDrawFX,
    triggerDiscardDrawFX
} from "./fx.js";
import {
    clearNotice,
    closeReveal,
    currentNoticeId,
    hideWinnerOverlay,
    showNotice,
    showReveal,
    showWinnerOverlay,
    toast
} from "./ui.js";

/* Memoria tra un render e l'altro. */
let prevCurrentIndex = null;
let prevAnimState = null;
let firstStateSeen = false;
let lastRoundResultId = 0;
let lastDiscardKey = null;

/* Scadenza del turno in orologio locale: il server manda i secondi rimasti,
   il ticker li scala da solo una volta al secondo. */
let turnDeadlineLocal = null;

/* Carta della mano selezionata per lo scarto. */
let selectedDiscardIndex = null;

export function render(){
    const state = currentState();
    if (!state) return;

    const view = computeView(state);

    announceTurnChange(view);
    applyHeader(view);
    playDrawAnimations(view);
    startTurnCountdown(view);
    renderEvents(view.events);

    const revealJustShown = maybeShowRoundResult(view.roundResult);

    renderPlayers(view.players, view.currentIndex);
    applyBanner(view.banner);
    applyViewerInfo(view);
    applyPending(view);
    renderHand(view.hand);
    applyOverlays(view, revealJustShown);
    applyControls(view);
}

/* =========================
   Pezzi della pagina
   ========================= */

function announceTurnChange(view){
    if (prevCurrentIndex !== null && prevCurrentIndex !== view.currentIndex) {
        if (view.viewerIndex === view.currentIndex) toast("È il tuo turno!");
    }
    prevCurrentIndex = view.currentIndex;
}

function applyHeader(view){
    const topRow = document.querySelector(".tableTopRow");
    if (topRow) topRow.classList.toggle("inPlay", view.tableInPlay);

    setText("phaseTxt", view.phaseText);
    setText("turnTxt", view.turnText);
    setText("deckNumTxt", String(view.deckSize));

    // Il mucchio degli scarti si ridisegna solo quando cambia davvero: altrimenti
    // il browser ricaricherebbe l'immagine a ogni messaggio del server.
    const discardTopTxt = document.getElementById("discardTopTxt");
    if (discardTopTxt && view.discardKey !== lastDiscardKey) {
        lastDiscardKey = view.discardKey;
        discardTopTxt.innerHTML = view.discardTop ? cardImgHtml(view.discardTop) : "—";
    }
}

function applyBanner(banner){
    const statusTxt = document.getElementById("statusTxt");
    if (statusTxt) {
        statusTxt.textContent = banner.title;
        statusTxt.title = banner.sub;
    }

    const statusPill = document.getElementById("statusPill");
    if (statusPill) {
        statusPill.textContent = banner.pillText;
        statusPill.classList.toggle("turn", banner.pillTurn);
    }
}

function applyViewerInfo(view){
    setText("meTxt", view.info.name);
    setText("livesTxt", view.info.lives);
    setText("handTitle", view.handTitle);
    setText("bestSuitTxt", view.bestSuitText);
}

function applyPending(view){
    const topRow = document.querySelector(".tableTopRow");
    if (topRow) topRow.classList.toggle("hasPendingTop", view.pending.show);
    setPendingTopVisible(view.pending.show, view.pending.card);

    // I pulsanti "tieni/scarta" seguono la mano scambiabile, ma vanno aggiornati
    // a ogni render: renderHand salta il lavoro quando la mano non è cambiata.
    const pendingActions = document.querySelector(".tableTopRow #pendingWrap .pendingActions");
    if (pendingActions) pendingActions.style.display = view.hand.allowSwap ? "" : "none";
}

function applyControls(view){
    setDisabled("btnDrawDeck", !view.buttons.drawDeck);
    setDisabled("btnDrawDiscard", !view.buttons.drawDiscard);
    setDisabled("btnKnock", !view.buttons.knock);

    const deckPile = document.getElementById("deckPile");
    const discardPile = document.getElementById("discardPile");
    if (deckPile) deckPile.classList.toggle("disabled", !view.buttons.drawDeck);
    if (discardPile) discardPile.classList.toggle("disabled", !view.buttons.drawDiscard);

    const btnStart = document.getElementById("btnStart");
    if (btnStart) {
        btnStart.style.display = view.start.visible ? "" : "none";
        btnStart.disabled = !view.start.enabled;
    }
    setText("startHint", view.start.hint);
}

function applyOverlays(view, revealJustShown){
    if (view.winner) {
        clearNotice();
        // chiude solo un reveal residuo di un round precedente
        if (!revealJustShown) closeReveal();
        showWinnerOverlay(view.winner.name, view.winner.isHost);
        return;
    }

    hideWinnerOverlay();
    if (view.notice && currentNoticeId() !== view.notice.id) {
        showNotice(view.notice.id, view.notice.msg);
    }
}

export function setPendingTopVisible(show, pendingCard){
    const cardBox = document.querySelector(".tableTopRow #pendingCardTxt");
    if (!cardBox) return;

    if (show) cardBox.innerHTML = cardImgHtml(pendingCard);
    else cardBox.innerHTML = "—";
}

/* =========================
   Confronti con lo stato precedente
   ========================= */

function playDrawAnimations(view){
    const prev = prevAnimState;
    const cur = view.animSnapshot;

    if (shouldAnimateDiscardDraw(prev, cur)) {
        triggerDiscardDrawFX(prev?.discardTop ?? null, view.currentIndex, view.players);
    }
    if (shouldAnimateDeckDraw(prev, cur)) {
        triggerDeckDrawFX();
    }
    prevAnimState = cur;
}

/**
 * L'esito di un round si mostra solo quando ne arriva uno nuovo. Al primo
 * stato dopo un (ri)caricamento si mostra solo se ancora recente, altrimenti
 * chi rientra si ritroverebbe davanti l'esito di mezz'ora prima.
 * @return true se l'overlay è stato aperto ora.
 */
function maybeShowRoundResult(rr){
    if (!firstStateSeen) {
        firstStateSeen = true;
        lastRoundResultId = rr ? rr.id : 0;
        if (rr && typeof rr.ageMs === "number" && rr.ageMs < RECENT_RESULT_MS) {
            showReveal(rr);
            return true;
        }
        return false;
    }

    if (rr && rr.id !== lastRoundResultId) {
        lastRoundResultId = rr.id;
        showReveal(rr);
        return true;
    }
    return false;
}

/* =========================
   Giocatori
   ========================= */

function renderPlayers(players, currentIndex){
    const root = document.getElementById("players");
    if (!root) return;
    root.innerHTML = "";

    players.forEach((p, i) => {
        const isSpectating = !!p.spectating;
        const isElim = !!p.eliminated && !isSpectating;
        const isCurrent = (i === currentIndex);

        const div = document.createElement("div");
        let cls = "p";
        if (isSpectating) cls += " spectating";
        else if (isElim) cls += " elim";
        if (isCurrent) cls += " current";
        div.className = cls;

        const name = escapeHtml(p.name ?? "—");
        const lives = (p.lives ?? 0);

        let stateLabel = "";
        if (isSpectating) stateLabel = '<span class="state">Spettatore</span>';
        else if (isElim) stateLabel = '<span class="state">Eliminato</span>';

        const tagClass = isCurrent ? "tag turn" : "tag";
        const tagText  = isCurrent ? "Turno" : "In attesa";

        div.innerHTML = '<div class="badge">' + (i+1) + '</div>'
            + '<div class="info">'
            + '<div class="name" title="' + name + '">' + name + '</div>'
            + '<div class="sub">'
            + '<span class="hearts" title="' + lives + ' vite">' + hearts(lives) + '</span>'
            + stateLabel
            + '</div></div>'
            + '<div class="' + tagClass + '">' + tagText + '</div>';

        root.appendChild(div);
    });
}

/* =========================
   Mano e selezione
   ========================= */

export function selectedDiscard(){
    return selectedDiscardIndex;
}

function confirmKeepButton(){
    return document.querySelector(".tableTopRow #btnConfirmKeep") || document.getElementById("btnConfirmKeep");
}

function setSelectedDiscard(idx){
    selectedDiscardIndex = idx;

    const btn = confirmKeepButton();
    if (btn) btn.disabled = (selectedDiscardIndex == null);

    document.querySelectorAll(".handCard").forEach(el => el.classList.remove("selected"));
    const target = document.querySelector(`.handCard[data-idx="${idx}"]`);
    if (target) target.classList.add("selected");
}

/** Nessuna carta selezionata: azzera indice, pulsante e evidenziazione. */
export function clearHandSelection(){
    selectedDiscardIndex = null;

    const btn = confirmKeepButton();
    if (btn) btn.disabled = true;

    document.querySelectorAll(".handCard").forEach(el => el.classList.remove("selected"));
}

function renderHand(hand){
    const handRow = document.getElementById("handRow");
    if (!handRow) return;

    const cards = hand.cards;
    const allowSwap = hand.allowSwap;

    // Se mano e modalità non sono cambiate, evita di ricostruire il DOM
    // (e di far ricaricare le immagini) a ogni messaggio del server.
    const sig = cards.map(c => cardKey(c) ?? "?").join("|") + (allowSwap ? "|swap" : "");
    if (sig === renderHand._sig) return;
    renderHand._sig = sig;

    handRow.innerHTML = "";

    if (!allowSwap) clearHandSelection();

    cards.forEach((c, idx) => {
        const card = document.createElement("div");
        card.className = "handCard";
        card.dataset.idx = String(idx);

        const val = (c?.value ?? "—");
        const label = (c?.label ?? "—");
        const imgUrl = cardImageUrl(c);

        if (allowSwap) {
            card.classList.add("selectable");
            card.onclick = () => setSelectedDiscard(idx);
        } else {
            card.onclick = null;
        }

        card.innerHTML = `
          <div class="top">
            <div class="val">${escapeHtml(String(val))}</div>
          </div>
          <div class="imgWrap">
            ${imgUrl ? `<img src="${imgUrl}" alt="${escapeHtml(label)}" />`
            : `<div style="font-weight:900;">${escapeHtml(label)}</div>`}
          </div>
          <div class="muted">
            ${escapeHtml(label)}
          </div>
        `;
        handRow.appendChild(card);
    });

    if (allowSwap && selectedDiscardIndex != null) setSelectedDiscard(selectedDiscardIndex);
}

/* =========================
   Timer di turno
   ========================= */

function startTurnCountdown(view){
    turnDeadlineLocal = (view.turnSecondsLeft != null)
        ? Date.now() + view.turnSecondsLeft * 1000
        : null;
    tickTurnTimer();
}

export function tickTurnTimer(){
    const el = document.getElementById("timerPill");
    if (!el) return;

    if (turnDeadlineLocal == null) {
        el.classList.remove("on", "low");
        return;
    }

    const secs = Math.max(0, Math.ceil((turnDeadlineLocal - Date.now()) / 1000));
    el.textContent = `${secs} s`;
    el.classList.add("on");
    el.classList.toggle("low", secs <= 15);
}

/* =========================
   Registro mosse
   ========================= */

function renderEvents(events){
    const root = document.getElementById("eventFeed");
    if (!root) return;

    if (!events.length) {
        root.innerHTML = '<div class="evt muted">Ancora nessuna mossa.</div>';
        return;
    }

    root.innerHTML = events.map(e => {
        const t = new Date(e.at ?? Date.now());
        const hh = String(t.getHours()).padStart(2, "0");
        const mm = String(t.getMinutes()).padStart(2, "0");
        return `<div class="evt"><span class="evtTime">${hh}:${mm}</span>${escapeHtml(e.msg ?? "")}</div>`;
    }).join("");
}

/* =========================
   Minuzie sul DOM
   ========================= */

function setText(id, text){
    const el = document.getElementById(id);
    if (el) el.textContent = text;
}

function setDisabled(id, disabled){
    const el = document.getElementById(id);
    if (el) el.disabled = disabled;
}
