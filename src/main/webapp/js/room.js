const ROOM_ID = window.__ROOM_ID__;
const CTX = window.__CTX__;

let ws = null;
let lastState = null;
let currentNoticeId = null;
let selectedDiscardIndex = null;
let prevCurrentIndex = null;
let prevAnimState = null;
let lastDiscardFxAt = 0;
let lastDeckFxAt = 0;

let reconnectAttempts = 0;
let reconnectTimer = null;

function escapeHtml(str){
    return (str ?? "")
        .replaceAll("&","&amp;")
        .replaceAll("<","&lt;")
        .replaceAll(">","&gt;")
        .replaceAll('"',"&quot;")
        .replaceAll("'","&#39;");
}

function wsUrl(roomId){
    const proto = (location.protocol === "https:") ? "wss" : "ws";
    return `${proto}://${location.host}${CTX}/ws/${encodeURIComponent(roomId)}`;
}

function suitToFilePrefix(suit){
    switch (String(suit ?? "").toUpperCase()) {
        case "DENARI": return "denari";
        case "COPPE": return "coppe";
        case "BASTONI": return "bastoni";
        case "SPADE": return "spade";
        default: return null;
    }
}

function rankToNumber(rank){
    switch (String(rank ?? "").toUpperCase()) {
        case "ASSO": return 1;
        case "DUE": return 2;
        case "TRE": return 3;
        case "QUATTRO": return 4;
        case "CINQUE": return 5;
        case "SEI": return 6;
        case "SETTE": return 7;
        case "FANTE": return 8;
        case "CAVALLO": return 9;
        case "RE": return 10;
        default: return null;
    }
}

function cardImageUrl(card){
    if (!card) return null;
    const s = suitToFilePrefix(card.suit);
    const n = rankToNumber(card.rank);
    if (!s || !n) return null;
    return `${CTX}/images/${s}_${n}.jpg`;
}

function cardImgHtml(card){
    if (!card) return "—";
    const url = cardImageUrl(card);
    const labelEsc = escapeHtml(card.label ?? "");
    if (!url) return labelEsc || "—";
    return `<img src="${url}" alt="${labelEsc}">`;
}

function cardKey(c){
    if (!c) return null;
    return `${c.suit ?? ""}|${c.rank ?? ""}|${c.value ?? ""}`;
}

function setPendingTopVisible(show, pendingCard){
    const cardBox = document.querySelector(".tableTopRow #pendingCardTxt");
    if (!cardBox) return;

    if (show) cardBox.innerHTML = cardImgHtml(pendingCard);
    else cardBox.innerHTML = "—";
}

/* =========================
   FX helpers
   ========================= */

function shouldAnimateDiscardDraw(prev, cur){
    if (!prev || !cur) return false;

    const inPlayPrev = (prev.phase === "PLAYING" || prev.phase === "KNOCK_CALLED");
    const inPlayCur  = (cur.phase === "PLAYING" || cur.phase === "KNOCK_CALLED");
    if (!inPlayPrev || !inPlayCur) return false;

    if ((prev.currentIndex ?? -1) !== (cur.currentIndex ?? -1)) return false;
    if ((prev.deckSize ?? 0) !== (cur.deckSize ?? 0)) return false;

    const prevTop = cardKey(prev.discardTop);
    const curTop  = cardKey(cur.discardTop);

    if (!prevTop) return false;
    if (prevTop === curTop) return false;

    return true;
}

function megaFlashDiscard(actorIdx, players){
    const pile = document.getElementById("discardPile");
    if (!pile) return;

    pile.classList.remove("discardMega");
    void pile.offsetWidth;
    pile.classList.add("discardMega");

    clearTimeout(megaFlashDiscard._tid);
    megaFlashDiscard._tid = setTimeout(() => pile.classList.remove("discardMega"), 1150);

    const name = (players && actorIdx >= 0 && actorIdx < players.length) ? (players[actorIdx]?.name ?? "") : "";
    showFxLabelAt("discard", name ? `PESCA DAGLI SCARTI • ${name}` : "PESCA DAGLI SCARTI");
}

function flyCardFromDiscard(prevTopCard){
    if (!prevTopCard) return;

    const srcEl = document.getElementById("discardTopTxt") || document.getElementById("discardPile");
    if (!srcEl) return;

    const src = srcEl.getBoundingClientRect();
    const srcCx = src.left + src.width/2;
    const srcCy = src.top + src.height/2;

    let dstCx = srcCx;
    let dstCy = srcCy - 180;

    const topRow = document.querySelector(".tableTopRow");
    const pendingCardEl = document.querySelector(".tableTopRow #pendingCardTxt");
    const pendingVisible = !!(topRow && topRow.classList.contains("hasPendingTop") && pendingCardEl);

    if (pendingVisible) {
        const dst = pendingCardEl.getBoundingClientRect();
        dstCx = dst.left + dst.width/2;
        dstCy = dst.top + dst.height/2;
    }

    const w = Math.max(120, Math.min(180, src.width));
    const h = Math.max(160, Math.min(240, src.height));

    const fx = document.createElement("div");
    fx.className = "fx-flycard";
    fx.style.width = `${w}px`;
    fx.style.height = `${h}px`;
    fx.style.left = `${srcCx - w/2}px`;
    fx.style.top  = `${srcCy - h/2}px`;

    const url = cardImageUrl(prevTopCard);
    if (url) {
        const img = document.createElement("img");
        img.src = url;
        img.alt = prevTopCard.label ?? "";
        fx.appendChild(img);
    } else {
        fx.textContent = prevTopCard.label ?? "Carta";
        fx.style.fontWeight = "1000";
        fx.style.padding = "10px";
        fx.style.textAlign = "center";
    }

    document.body.appendChild(fx);

    const dx = dstCx - srcCx;
    const dy = dstCy - srcCy;

    fx.animate([
        { transform: "translate3d(0,0,0) scale(1) rotate(0deg)", opacity: 1 },
        { transform: `translate3d(${dx*0.65}px, ${dy*0.65}px, 0) scale(1.18) rotate(-8deg)`, opacity: 1, offset: 0.6 },
        { transform: `translate3d(${dx}px, ${dy}px, 0) scale(0.92) rotate(0deg)`, opacity: 0 }
    ], {
        duration: 900,
        easing: "cubic-bezier(.2,.9,.2,1)"
    }).onfinish = () => fx.remove();
}

function triggerDiscardDrawFX(prevTopCard, actorIdx, players){
    const now = Date.now();
    if (now - lastDiscardFxAt < 250) return;
    lastDiscardFxAt = now;

    megaFlashDiscard(actorIdx, players);
    flyCardFromDiscard(prevTopCard);
}

function shouldAnimateDeckDraw(prev, cur){
    if (!prev || !cur) return false;

    const inPlayPrev = (prev.phase === "PLAYING" || prev.phase === "KNOCK_CALLED");
    const inPlayCur  = (cur.phase === "PLAYING" || cur.phase === "KNOCK_CALLED");
    if (!inPlayPrev || !inPlayCur) return false;

    if ((prev.currentIndex ?? -1) !== (cur.currentIndex ?? -1)) return false;

    const prevDeck = (prev.deckSize ?? 0);
    const curDeck  = (cur.deckSize ?? 0);

    return ((prevDeck - curDeck) === 1);
}

function flyCardFromDeck(){
    const pile = document.getElementById("deckPile");
    if (!pile) return;

    const src = pile.getBoundingClientRect();
    const srcCx = src.left + src.width/2;
    const srcCy = src.top + src.height/2;

    let dstCx = srcCx;
    let dstCy = srcCy - 200;

    const topRow = document.querySelector(".tableTopRow");
    const pendingCardEl = document.querySelector(".tableTopRow #pendingCardTxt");
    const pendingVisible = !!(topRow && topRow.classList.contains("hasPendingTop") && pendingCardEl);

    if (pendingVisible) {
        const dst = pendingCardEl.getBoundingClientRect();
        dstCx = dst.left + dst.width/2;
        dstCy = dst.top + dst.height/2;
    }

    const w = 130;
    const h = 185;

    const fx = document.createElement("div");
    fx.className = "fx-flycard";
    fx.style.width = `${w}px`;
    fx.style.height = `${h}px`;
    fx.style.left = `${srcCx - w/2}px`;
    fx.style.top  = `${srcCy - h/2}px`;
    fx.style.border = "1px solid rgba(255,255,255,.14)";
    fx.style.background = "rgba(18,26,43,.78)";
    fx.style.boxShadow = "0 14px 26px rgba(0,0,0,.45)";
    fx.style.opacity = "0.92";

    const deckImg = document.querySelector("#deckPile .cardBox img");
    const backSrc = deckImg?.src || `${CTX}/images/retro_mazzo.jpg`;

    const img = document.createElement("img");
    img.src = backSrc;
    img.alt = "Retro carta";
    img.style.filter = "drop-shadow(0 10px 14px rgba(0,0,0,.35))";
    fx.appendChild(img);

    document.body.appendChild(fx);

    const dx = dstCx - srcCx;
    const dy = dstCy - srcCy;

    fx.animate([
        { transform: "translate3d(0,0,0) scale(0.98) rotate(0deg)", opacity: 0.0 },
        { transform: `translate3d(${dx*0.25}px, ${dy*0.25}px, 0) scale(1.01) rotate(2deg)`, opacity: 0.92, offset: 0.25 },
        { transform: `translate3d(${dx}px, ${dy}px, 0) scale(0.92) rotate(0deg)`, opacity: 0.0 }
    ], {
        duration: 620,
        easing: "cubic-bezier(.25,.9,.25,1)"
    }).onfinish = () => fx.remove();
}

function triggerDeckDrawFX(){
    const now = Date.now();
    if (now - lastDeckFxAt < 250) return;
    lastDeckFxAt = now;

    flyCardFromDeck();
}

function showFxLabelAt(which, text){
    const anchor = (which === "deck")
        ? document.getElementById("deckPile")
        : (document.getElementById("discardTopTxt") || document.getElementById("discardPile"));

    if (!anchor) return;

    const r = anchor.getBoundingClientRect();
    const x = r.left + r.width / 2;
    const y = r.top + 10;

    const el = document.createElement("div");
    el.className = "fx-discard-label";
    el.textContent = text;
    el.style.left = `${x}px`;
    el.style.top  = `${y}px`;

    document.body.appendChild(el);
    setTimeout(() => el.remove(), 1300);
}

/* =========================
   WS connect + reconnect
   ========================= */

function connect(){
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;

    clearTimeout(reconnectTimer);
    reconnectTimer = null;

    ws = new WebSocket(wsUrl(ROOM_ID));

    ws.onopen = () => {
        reconnectAttempts = 0;
    };

    ws.onmessage = (ev) => {
        try {
            lastState = JSON.parse(ev.data);
            render();
        } catch (e) {
            console.error("Bad JSON", e);
        }
    };

    ws.onclose = (ev) => {
        const reason = String(ev.reason || "");
        const fatal = [
            "Room not found",
            "Not joined",
            "No HTTP session",
            "Invalid player"
        ].some(x => reason.includes(x));

        if (fatal) {
            console.warn("WS closed (fatal):", ev.code, reason);
            return;
        }

        const delay = Math.min(5000, 600 * (2 ** Math.min(reconnectAttempts, 4)));
        reconnectAttempts++;

        console.warn("WS closed. Reconnect in", delay, "ms", ev.code, reason);
        toast("Connessione persa… riconnessione");
        reconnectTimer = setTimeout(connect, delay);
    };

    ws.onerror = () => {
        try { ws.close(); } catch (e) {}
    };
}

function sendAction(action, arg){
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    if (arg === undefined) ws.send(`ACTION:${action}`);
    else ws.send(`ACTION:${action}:${arg}`);
}

function restartGame(){
    sendAction("restartGame");
}

function pileClick(which){
    if (!lastState) return;

    const phase = lastState.phase ?? "";
    const viewerIndex = lastState.viewerIndex ?? -1;
    const currentIndex = lastState.currentIndex ?? -1;

    const players = Array.isArray(lastState.players) ? lastState.players : [];
    const viewerObj = (viewerIndex >= 0 && viewerIndex < players.length) ? players[viewerIndex] : null;

    if (viewerObj?.eliminated) return;

    const myTurn = (viewerIndex === currentIndex);
    const inPlay = (phase === "PLAYING" || phase === "KNOCK_CALLED");
    const hasPending = !!(viewerObj && viewerObj.pendingDraw);

    if (!(myTurn && inPlay && !hasPending)) return;

    if (which === "deck") sendAction("drawDeck");
    if (which === "discard" && !!lastState.discardTop) sendAction("drawDiscard");
}

function hearts(n){
    n = Math.max(0, n|0);
    return "♥".repeat(n);
}

function isJoinedPlayer(p){
    if (p && typeof p.joined === "boolean") return p.joined;
    const nm = String(p?.name ?? "");
    return nm.length > 0 && !nm.toLowerCase().startsWith("slot ");
}

function toast(msg){
    const t = document.getElementById("toast");
    if (!t) return;
    t.textContent = msg ?? "";
    t.classList.add("on");
    clearTimeout(toast._tid);
    toast._tid = setTimeout(() => t.classList.remove("on"), 1600);
}

/* =========================
   RENDER: players
   ========================= */

function renderPlayers(players, currentIndex){
    const root = document.getElementById("players");
    if (!root) return;
    root.innerHTML = "";

    players.forEach((p, i) => {
        const div = document.createElement("div");
        div.className = "p" + (p.eliminated ? " elim" : "") + (i === currentIndex ? " current" : "");

        const name = escapeHtml(p.name ?? "—");
        const lives = (p.lives ?? 0);

        // ✅ Qui: NIENTE “Carte:” e cuori sempre sotto
        div.innerHTML = `
          <div class="badge">${i+1}</div>
          <div class="info">
            <div class="name" title="${name}">${name}</div>
            <div class="sub">
              <span class="hearts" title="${lives} vite">${hearts(lives)}</span>
              ${p.eliminated ? `<span class="state">Eliminato</span>` : ""}
            </div>
          </div>
          <div class="tag ${i === currentIndex ? "turn" : ""}">${i === currentIndex ? "Turno" : "In attesa"}</div>
        `;

        root.appendChild(div);
    });
}

/* =========================
   Hand selection
   ========================= */

function setSelectedDiscard(idx){
    selectedDiscardIndex = idx;

    const btn = document.querySelector(".tableTopRow #btnConfirmKeep") || document.getElementById("btnConfirmKeep");
    if (btn) btn.disabled = (selectedDiscardIndex == null);

    document.querySelectorAll(".handCard").forEach(el => el.classList.remove("selected"));
    const target = document.querySelector(`.handCard[data-idx="${idx}"]`);
    if (target) target.classList.add("selected");
}

function confirmKeep(){
    if (selectedDiscardIndex == null) return;

    sendAction("keep", selectedDiscardIndex);

    // reset UI immediato (poi arriva lo stato dal server)
    selectedDiscardIndex = null;

    const btn = document.querySelector(".tableTopRow #btnConfirmKeep") || document.getElementById("btnConfirmKeep");
    if (btn) btn.disabled = true;

    document.querySelectorAll(".handCard").forEach(el => el.classList.remove("selected"));
    setPendingTopVisible(false, null);
}

function renderHand(viewHand, allowSwap){
    const handRow = document.getElementById("handRow");
    if (!handRow) return;
    handRow.innerHTML = "";

    const hand = Array.isArray(viewHand) ? viewHand : [];

    if (!allowSwap) {
        selectedDiscardIndex = null;
        const btn = document.querySelector(".tableTopRow #btnConfirmKeep") || document.getElementById("btnConfirmKeep");
        if (btn) btn.disabled = true;
        document.querySelectorAll(".handCard").forEach(el => el.classList.remove("selected"));
    }

    hand.forEach((c, idx) => {
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
          <div class="muted" style="font-size:12px; text-align:center;">
            ${escapeHtml(label)}
          </div>
        `;
        handRow.appendChild(card);
    });

    if (allowSwap && selectedDiscardIndex != null) setSelectedDiscard(selectedDiscardIndex);
}

/* =========================
   Notice + winner
   ========================= */

function showNotice(id, msg){
    currentNoticeId = id;
    const m = document.getElementById("noticeMsg");
    const ov = document.getElementById("noticeOverlay");
    if (m) m.textContent = msg ?? "—";
    if (ov) ov.style.display = "flex";
}

function hideNotice(){
    const ov = document.getElementById("noticeOverlay");
    if (ov) ov.style.display = "none";
}

function ackNotice(){
    if (currentNoticeId != null) {
        sendAction("ackNotice", currentNoticeId);
    }
    currentNoticeId = null;
    hideNotice();

    try {
        const vi = lastState?.viewerIndex;
        if (vi != null && lastState?.players?.[vi]) {
            lastState.players[vi].noticeId = null;
            lastState.players[vi].noticeMsg = null;
        }
    } catch (e) {}
}

function showWinnerOverlay(name, isHost){
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

function hideWinnerOverlay(){
    const ov = document.getElementById("winnerOverlay");
    if (ov) ov.style.display = "none";
}

function renderBanner(phase, viewerIndex, currentIndex, players){
    const statusTxt = document.getElementById("statusTxt");
    const statusPill = document.getElementById("statusPill");

    const meTurn = (viewerIndex === currentIndex);
    const curName = (currentIndex >= 0 && currentIndex < players.length)
        ? (players[currentIndex]?.name ?? ("Player " + (currentIndex+1)))
        : "—";

    let title = "—";
    let sub = "";
    let pillText = "—";
    let pillClass = "pill";

    if (phase === "WAITING_FOR_PLAYERS") {
        title = "In attesa di giocatori";
        sub = "Quando siete pronti, il Player 1 può avviare la partita.";
        pillText = "WAITING";
    } else if (phase === "GAME_OVER") {
        title = "Partita terminata";
        sub = "È stato dichiarato un vincitore.";
        pillText = "GAME OVER";
    } else if (meTurn) {
        title = "È il tuo turno";
        sub = (phase === "KNOCK_CALLED")
            ? "Turni finali dopo bussata: pesca e chiudi il turno."
            : "Pesca una carta o bussa (se vuoi chiudere).";
        pillText = "TOCCA A TE";
        pillClass = "pill turn";
    } else {
        title = "In attesa";
        sub = `Sta giocando: ${curName}`;
        pillText = "ATTENDI";
    }

    if (statusTxt) {
        statusTxt.textContent = title;
        statusTxt.title = sub;
    }
    if (statusPill) {
        statusPill.textContent = pillText;
        statusPill.className = pillClass;
    }
}

/* =========================
   Main render
   ========================= */

function render(){
    if (!lastState) return;

    const phase = lastState.phase ?? "—";
    const topRow = document.querySelector(".tableTopRow");
    if (topRow){
        const inPlayUI = (phase === "PLAYING" || phase === "KNOCK_CALLED" || phase === "GAME_OVER");
        topRow.classList.toggle("inPlay", inPlayUI);
    }

    const viewerIndex = lastState.viewerIndex ?? -1;
    const currentIndex = lastState.currentIndex ?? -1;
    const winnerIndex = lastState.winnerIndex;

    if (prevCurrentIndex !== null && prevCurrentIndex !== currentIndex) {
        if (viewerIndex === currentIndex) toast("È il tuo turno!");
    }
    prevCurrentIndex = currentIndex;

    const phaseTxt = document.getElementById("phaseTxt");
    const turnTxt = document.getElementById("turnTxt");
    if (phaseTxt) phaseTxt.textContent = phase;
    if (turnTxt) turnTxt.textContent = (currentIndex >= 0 ? (currentIndex + 1) : "—");

    const deckNum = (lastState.deckSize ?? 0);
    const deckNumTxt = document.getElementById("deckNumTxt");
    if (deckNumTxt) deckNumTxt.textContent = String(deckNum);

    const discardTopTxt = document.getElementById("discardTopTxt");
    if (discardTopTxt) discardTopTxt.innerHTML = lastState.discardTop ? cardImgHtml(lastState.discardTop) : "—";

    const players = Array.isArray(lastState.players) ? lastState.players : [];

    const curSnap = {
        phase: lastState.phase,
        currentIndex: lastState.currentIndex,
        deckSize: lastState.deckSize,
        discardTop: lastState.discardTop
    };
    const prevSnap = prevAnimState;

    if (shouldAnimateDiscardDraw(prevSnap, curSnap)) {
        const prevTop = prevSnap?.discardTop ?? null;
        triggerDiscardDrawFX(prevTop, currentIndex, players);
    }
    if (shouldAnimateDeckDraw(prevSnap, curSnap)) {
        triggerDeckDrawFX();
    }
    prevAnimState = curSnap;

    renderPlayers(players, currentIndex);
    renderBanner(phase, viewerIndex, currentIndex, players);

    const viewerObj = (viewerIndex >= 0 && viewerIndex < players.length) ? players[viewerIndex] : null;

    const spectator = !!lastState.viewerEliminated;
    const handViewIndex = (lastState.handViewIndex ?? viewerIndex);
    const handObj = (handViewIndex >= 0 && handViewIndex < players.length) ? players[handViewIndex] : null;

    const infoObj = spectator ? handObj : viewerObj;

    const meTxt = document.getElementById("meTxt");
    if (meTxt) meTxt.textContent = infoObj?.name ?? "—";

    const livesTxt = document.getElementById("livesTxt");
    if (livesTxt) livesTxt.textContent = (infoObj && infoObj.lives != null) ? String(infoObj.lives) : "—";

    const handTitle = document.getElementById("handTitle");
    if (handTitle) {
        handTitle.textContent = spectator
            ? `Mano di ${handObj?.name ?? "—"} (spettatore)`
            : "La tua mano";
    }

    const inPlay = (phase === "PLAYING" || phase === "KNOCK_CALLED");
    const myTurn = (!spectator && viewerIndex === currentIndex);

    const viewPending = lastState.viewPending ?? null;
    const showPendingTop = !!(inPlay && viewPending);

    if (topRow) topRow.classList.toggle("hasPendingTop", showPendingTop);
    setPendingTopVisible(showPendingTop, viewPending);

    const pendingActions = document.querySelector(".tableTopRow #pendingWrap .pendingActions");
    const allowSwap = !!(myTurn && inPlay && viewPending);
    if (pendingActions) pendingActions.style.display = allowSwap ? "" : "none";

    renderHand(lastState.viewHand, allowSwap);

    const bestSuitTxt = document.getElementById("bestSuitTxt");
    if (bestSuitTxt) {
        const lbl = lastState.viewBestSuitLabel;
        const val = lastState.viewBestSuitValue;
        if (lbl != null && val !== undefined && val !== null) bestSuitTxt.textContent = `${lbl} — ${val} punti`;
        else bestSuitTxt.textContent = "—";
    }

    if (phase === "GAME_OVER" && winnerIndex != null && winnerIndex >= 0 && winnerIndex < players.length) {
        hideNotice();
        currentNoticeId = null;

        const wName = players[winnerIndex]?.name ?? ("Player " + (winnerIndex + 1));
        const isHost = (viewerIndex === 0);
        showWinnerOverlay(wName, isHost);
    } else {
        hideWinnerOverlay();

        const nId = viewerObj?.noticeId;
        const nMsg = viewerObj?.noticeMsg;
        if (nId != null && typeof nMsg === "string" && nMsg.length > 0) {
            if (currentNoticeId !== nId) showNotice(nId, nMsg);
        }
    }

    const hasPendingSelf = !!(viewerObj && viewerObj.pendingDraw);

    const canDrawDeck = (myTurn && inPlay && !hasPendingSelf);
    const canDrawDiscard = (myTurn && inPlay && !hasPendingSelf && !!lastState.discardTop);

    const btnDrawDeck = document.getElementById("btnDrawDeck");
    const btnDrawDiscard = document.getElementById("btnDrawDiscard");
    const btnKnock = document.getElementById("btnKnock");

    if (btnDrawDeck) btnDrawDeck.disabled = !canDrawDeck;
    if (btnDrawDiscard) btnDrawDiscard.disabled = !canDrawDiscard;
    if (btnKnock) btnKnock.disabled = !(myTurn && phase === "PLAYING" && !hasPendingSelf);

    const deckPile = document.getElementById("deckPile");
    const discardPile = document.getElementById("discardPile");
    if (deckPile) deckPile.classList.toggle("disabled", !canDrawDeck);
    if (discardPile) discardPile.classList.toggle("disabled", !canDrawDiscard);

    const btnStart = document.getElementById("btnStart");
    const startHint = document.getElementById("startHint");
    if (btnStart) {
        const joinedCount = players.reduce((acc, p) => acc + (isJoinedPlayer(p) ? 1 : 0), 0);
        const isHost = (viewerIndex === 0);
        const isWaiting = (phase === "WAITING_FOR_PLAYERS");

        btnStart.style.display = isWaiting ? "" : "none";

        const canStart = (isWaiting && isHost && joinedCount >= 2);
        btnStart.disabled = !canStart;

        if (startHint) {
            if (!isWaiting) startHint.textContent = "";
            else if (!isHost) startHint.textContent = "Solo il creatore della stanza (Player 1) può avviare la partita.";
            else if (joinedCount < 2) startHint.textContent = "Servono almeno 2 giocatori per iniziare.";
            else startHint.textContent = "Pronto: puoi avviare la partita.";
        }
    }
}

/* =========================
   Invite
   ========================= */

function inviteUrl(){
    return `${location.origin}${CTX}/join?room=${encodeURIComponent(ROOM_ID)}`;
}

function fallbackCopy(){
    const inp = document.getElementById("inviteLink");
    if (!inp) return;
    inp.focus();
    inp.select();
    try { document.execCommand("copy"); } catch (e) {}
}

function copyInvite(){
    const url = inviteUrl();
    const inp = document.getElementById("inviteLink");
    if (inp) inp.value = url;

    if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(url).catch(() => fallbackCopy());
    } else {
        fallbackCopy();
    }
}

document.addEventListener("DOMContentLoaded", () => {
    const inp = document.getElementById("inviteLink");
    if (inp) inp.value = inviteUrl();

    setPendingTopVisible(false, null);
    connect();
});
