<%@ page contentType="text/html; charset=UTF-8" isELIgnored="true" %>
<%
    String roomId = (String) request.getAttribute("roomId");
    if (roomId == null) roomId = request.getParameter("room");
    if (roomId != null) roomId = roomId.trim().toUpperCase();

    String err = (String) request.getAttribute("error");
    String ctx = request.getContextPath();
%>

<%!
    private static String h(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;")
                .replace("<","&lt;")
                .replace(">","&gt;")
                .replace("\"","&quot;")
                .replace("'","&#39;");
    }

    private static String js(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // switch classico: Jasper compila le JSP a un source level più basso di Java 14
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"':  out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                // impedisce di chiudere il blocco <script> dall'interno della stringa
                case '<':  out.append("\\u003c"); break;
                case '>':  out.append("\\u003e"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int)c));
                    else out.append(c);
            }
        }
        return out.toString();
    }
%>

<!doctype html>
<html lang="it">
<head>
    <meta charset="UTF-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
    <title>Gioco del 31</title>

    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Lora:wght@400;500;600;700&family=Open+Sans:wght@400;500;700;800&display=swap">

    <link rel="stylesheet" href="<%= h(ctx) %>/css/base.css?v=5">
    <link rel="stylesheet" href="<%= h(ctx) %>/css/components.css?v=7">
    <link rel="stylesheet" href="<%= h(ctx) %>/css/room.css?v=13">
</head>

<body>
<header>
    <h1>31</h1>

    <div class="metaLine">
        Room: <b><span id="roomIdTxt"><%= (roomId != null ? h(roomId) : "—") %></span></b>
        <span class="sep">•</span>
        Fase: <b><span id="phaseTxt">—</span></b>
        <span class="sep">•</span>
        Turno: <b><span id="turnTxt">—</span></b>
        <span class="sep">•</span>
        <b><span id="statusTxt">—</span></b>
    </div>

    <div class="pill" id="timerPill" title="Tempo rimasto per il turno">—</div>
</header>

<div class="wrap">

    <% if (err != null) { %>
    <div class="alert"><b>Errore:</b> <%= h(err) %></div>
    <% } %>

    <% if (roomId == null || roomId.isBlank()) { %>

    <div class="panel">
        <h2>Room non specificata</h2>
        <div class="muted">Apri la pagina come <b>/room?room=ABCD</b> oppure entra da Join.</div>
    </div>

    <% } else { %>

    <div class="players" id="players"></div>

    <div class="grid">
        <div class="panel">
            <h2>Tavolo</h2>

            <div class="tableTopRow">

                <!-- AREA SINISTRA: Invito -->
                <div class="leftArea">
                    <div class="pile sidePile invitePile">
                        <h2>Invita</h2>

                        <div class="btnRow inviteRow">
                            <input id="inviteLink" class="inviteInput" readonly value=""/>
                            <button class="primary" type="button" onclick="copyInvite()">Copia link</button>
                        </div>
                    </div>

                    <div class="pill" id="statusPill">—</div>
                </div>

                <!-- AREA GIOCO (centrale): Pending + Mazzo + Scarti -->
                <div class="playArea">

                    <div id="pendingWrap" class="pile pendingPile">
                        <div class="title">Carta pescata</div>
                        <div class="cardBox pendingCard" id="pendingCardTxt">—</div>

                        <div class="btnRow pendingActions">
                            <button id="btnConfirmKeep" class="ok" type="button" disabled onclick="confirmKeep()">
                                Scarta quella selezionata
                            </button>
                            <button class="danger" type="button" onclick="sendAction('reject')">
                                Scarta quella pescata
                            </button>
                        </div>
                    </div>

                    <div class="pile pileClickable pileTall" id="deckPile" onclick="pileClick('deck')">
                        <div class="title">
                            Mazzo: <span id="deckNumTxt" class="deckNum">—</span>
                        </div>

                        <div class="cardBox tall">
                            <img src="<%= h(ctx) %>/images/retro_mazzo.jpg" alt="Retro carta">
                        </div>

                        <button id="btnDrawDeck" class="primary" type="button" onclick="event.stopPropagation(); pileClick('deck')">
                            Pesca dal mazzo
                        </button>
                    </div>


                    <div class="pile pileClickable pileTall" id="discardPile" onclick="pileClick('discard')">
                        <div class="title">Scarti (cima)</div>

                        <div class="cardBox tall" id="discardTopTxt">—</div>

                        <button id="btnDrawDiscard" class="primary" type="button" onclick="event.stopPropagation(); pileClick('discard')">
                            Pesca dagli scarti
                        </button>
                    </div>
                </div>

                <div class="sideArea">

                    <div class="pile sidePile">

                        <h2>Comandi</h2>

                        <div class="btnRow cmdRow">
                            <button id="btnKnock" class="primary" type="button" onclick="sendAction('knock')">Bussa</button>

                            <form method="post" action="<%= h(ctx) %>/start">
                                <button id="btnStart" class="ok" type="submit" disabled>Inizia gioco</button>
                            </form>

                            <form method="post" action="<%= h(ctx) %>/leave">
                                <button class="danger" type="submit">Esci</button>
                            </form>
                        </div>

                        <div id="startHint" class="muted"></div>

                        <div class="sideDivider"></div>

                        <h2>Al tavolo</h2>

                        <div id="eventFeed" class="eventFeed" aria-live="polite"></div>
                    </div>
                </div>
            </div>

            <hr class="hr">

            <div class="tableBottomRow">
                <h2 id="handTitle">La tua mano</h2>

                <div class="handRow" id="handRow"></div>

                <div class="handInfoBelow">
                    <div class="muted">Player: <b><span id="meTxt">-</span></b></div>
                    <div class="muted">Vite: <b><span id="livesTxt">-</span></b></div>
                    <div class="muted">Miglior seme: <b><span id="bestSuitTxt">-</span></b></div>
                </div>
            </div>
        </div>
    </div>

    <!-- FINE ROUND: MANI RIVELATE -->
    <div id="revealOverlay" class="modalOverlay" role="dialog" aria-modal="true" aria-live="polite">
        <div class="modalCard revealCard">
            <h3 class="modalTitle">Fine round</h3>
            <p id="revealMsg" class="modalMsg">—</p>
            <div id="revealHands" class="revealHands"></div>
            <div class="modalActions">
                <button class="ok" type="button" onclick="closeReveal()">OK</button>
            </div>
        </div>
    </div>

    <!-- MODAL NOTICE -->
    <div id="noticeOverlay" class="modalOverlay" role="dialog" aria-modal="true" aria-live="polite">
        <div class="modalCard">
            <h3 class="modalTitle">Notifica</h3>
            <p id="noticeMsg" class="modalMsg">—</p>
            <div class="modalActions">
                <button class="ok" type="button" onclick="ackNotice()">OK</button>
            </div>
        </div>
    </div>

    <!-- WINNER OVERLAY -->
    <div id="winnerOverlay" class="modalOverlay" role="dialog" aria-modal="true" aria-live="polite">
        <div class="modalCard">
            <div class="winnerTitle">Vincitore</div>
            <h2 id="winnerName" class="winnerName">—</h2>
            <p id="winnerSub" class="winnerSub">—</p>
            <div class="modalActions">
                <button id="btnRestart" class="ok" type="button" onclick="restartGame()">Rigioca</button>
            </div>
        </div>
    </div>

    <div id="toast" class="toast">—</div>

    <!-- Banner di riconnessione persistente: stile .toast, ma su una riga
         propria (.reconnect) per non sovrapporsi ai toast di gioco -->
    <div id="reconnectBanner" class="toast reconnect" role="status" aria-live="polite">
        <span id="reconnectMsg">Connessione persa… riconnessione in corso</span>
        <button id="reconnectRetry" class="ok" type="button"
                style="display:none; margin-left:10px;" onclick="retryNow()">Riprova</button>
    </div>

    <script>
        window.__ROOM_ID__ = "<%= js(roomId) %>";
        window.__CTX__ = "<%= js(ctx) %>";
    </script>
    <script defer src="<%= h(ctx) %>/js/room.js?v=12"></script>

    <% } %>
</div>
</body>
</html>
