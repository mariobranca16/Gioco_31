/* Animazioni della pesca. Sono decorative: se un elemento non c'è o lo stato
   non basta a capire cosa è successo, si rinuncia in silenzio. */

import { CTX, FX_THROTTLE_MS } from "./config.js";
import { cardImageUrl, cardKey, isInPlay } from "./util.js";

let lastDiscardFxAt = 0;
let lastDeckFxAt = 0;

/** Centro del riquadro "Carta pescata" se visibile, altrimenti il fallback. */
function pendingTargetCenter(fallbackCx, fallbackCy){
    const topRow = document.querySelector(".tableTopRow");
    const pendingCardEl = document.querySelector(".tableTopRow #pendingCardTxt");
    if (topRow && topRow.classList.contains("hasPendingTop") && pendingCardEl) {
        const dst = pendingCardEl.getBoundingClientRect();
        return { x: dst.left + dst.width/2, y: dst.top + dst.height/2 };
    }
    return { x: fallbackCx, y: fallbackCy };
}

export function shouldAnimateDiscardDraw(prev, cur){
    if (!prev || !cur) return false;

    if (!isInPlay(prev.phase) || !isInPlay(cur.phase)) return false;

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
    showDiscardFxLabel(name ? `PESCA DAGLI SCARTI • ${name}` : "PESCA DAGLI SCARTI");
}

function flyCardFromDiscard(prevTopCard){
    if (!prevTopCard) return;

    const srcEl = document.getElementById("discardTopTxt") || document.getElementById("discardPile");
    if (!srcEl) return;

    const src = srcEl.getBoundingClientRect();
    const srcCx = src.left + src.width/2;
    const srcCy = src.top + src.height/2;

    const { x: dstCx, y: dstCy } = pendingTargetCenter(srcCx, srcCy - 180);

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
        fx.style.fontWeight = "700";
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

export function triggerDiscardDrawFX(prevTopCard, actorIdx, players){
    const now = Date.now();
    if (now - lastDiscardFxAt < FX_THROTTLE_MS) return;
    lastDiscardFxAt = now;

    megaFlashDiscard(actorIdx, players);
    flyCardFromDiscard(prevTopCard);
}

export function shouldAnimateDeckDraw(prev, cur){
    if (!prev || !cur) return false;

    if (!isInPlay(prev.phase) || !isInPlay(cur.phase)) return false;

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

    const { x: dstCx, y: dstCy } = pendingTargetCenter(srcCx, srcCy - 200);

    const w = 130;
    const h = 185;

    const fx = document.createElement("div");
    fx.className = "fx-flycard";
    fx.style.width = `${w}px`;
    fx.style.height = `${h}px`;
    fx.style.left = `${srcCx - w/2}px`;
    fx.style.top  = `${srcCy - h/2}px`;
    fx.style.border = "1px solid rgba(217,201,163,.9)";
    fx.style.background = "rgba(253,249,238,.95)";
    fx.style.boxShadow = "0 10px 24px rgba(14,28,18,.40)";
    fx.style.opacity = "0.95";

    const deckImg = document.querySelector("#deckPile .cardBox img");
    const backSrc = deckImg?.src || `${CTX}/images/retro_mazzo.jpg`;

    const img = document.createElement("img");
    img.src = backSrc;
    img.alt = "Retro carta";
    img.style.filter = "drop-shadow(0 4px 8px rgba(14,28,18,.30))";
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

export function triggerDeckDrawFX(){
    const now = Date.now();
    if (now - lastDeckFxAt < FX_THROTTLE_MS) return;
    lastDeckFxAt = now;

    flyCardFromDeck();
}

function showDiscardFxLabel(text){
    const anchor = document.getElementById("discardTopTxt") || document.getElementById("discardPile");
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
