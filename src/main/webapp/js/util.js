/* Funzioni pure: niente DOM, niente stato. Le usano tutti i moduli che
   compongono HTML o leggono le carte arrivate dal server. */

import { CTX } from "./config.js";

export function escapeHtml(str){
    return (str ?? "")
        .replaceAll("&","&amp;")
        .replaceAll("<","&lt;")
        .replaceAll(">","&gt;")
        .replaceAll('"',"&quot;")
        .replaceAll("'","&#39;");
}

export function isInPlay(phase){
    return phase === "PLAYING" || phase === "KNOCK_CALLED";
}

export function hearts(n){
    n = Math.max(0, n|0);
    return "♥".repeat(n);
}

export function isJoinedPlayer(p){
    if (p && typeof p.joined === "boolean") return p.joined;
    const nm = String(p?.name ?? "");
    return nm.length > 0 && !nm.toLowerCase().startsWith("slot ");
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

export function cardImageUrl(card){
    if (!card) return null;
    const s = suitToFilePrefix(card.suit);
    const n = rankToNumber(card.rank);
    if (!s || !n) return null;
    return `${CTX}/images/${s}_${n}.jpg`;
}

export function cardImgHtml(card){
    if (!card) return "—";
    const url = cardImageUrl(card);
    const labelEsc = escapeHtml(card.label ?? "");
    if (!url) return labelEsc || "—";
    return `<img src="${url}" alt="${labelEsc}">`;
}

/** Identità di una carta, per capire se il mucchio è cambiato. */
export function cardKey(c){
    if (!c) return null;
    return `${c.suit ?? ""}|${c.rank ?? ""}|${c.value ?? ""}`;
}
