<%@ page contentType="text/html; charset=UTF-8" %>
<%
    String ctx = request.getContextPath();
    String method = request.getMethod();

    String roomParam = request.getParameter("room");
    if (roomParam == null) roomParam = request.getParameter("roomId");

    String roomAttr = (String) request.getAttribute("room");
    if (roomAttr == null) roomAttr = (String) request.getAttribute("roomId");

    String source = request.getParameter("source"); // invite | manual | null

    String roomRaw = (roomParam != null) ? roomParam : roomAttr;

    String room = null;
    if (roomRaw != null) {
        String tmp = roomRaw.trim().toUpperCase();
        if (!tmp.isBlank()) room = tmp;
    }
    boolean hasRoom = (room != null);

    boolean inviteMode =
            ("invite".equalsIgnoreCase(source)) ||
                    ("GET".equalsIgnoreCase(method) && roomParam != null && !roomParam.isBlank());

    String error = (String) request.getAttribute("error");

    String nameVal = request.getParameter("name");
    if (nameVal == null) nameVal = "";

    String playersVal = request.getParameter("players");
    if (playersVal == null || playersVal.isBlank()) playersVal = "4";
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
%>

<!doctype html>
<html lang="it">
<head>
    <meta charset="UTF-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
    <title>Gioco del 31 - Entra</title>

    <link rel="stylesheet" href="<%= h(ctx) %>/css/base.css?v=1">
    <link rel="stylesheet" href="<%= h(ctx) %>/css/components.css?v=1">
    <link rel="stylesheet" href="<%= h(ctx) %>/css/join.css?v=1">
</head>

<body class="joinPage">
<div class="card">
    <h2 class="pageTitle"><%= inviteMode ? "Entra nella stanza" : "Gioco del 31" %></h2>

    <% if (inviteMode && hasRoom) { %>
    <div class="muted top-info">Stanza: <b><%= h(room) %></b></div>
    <div class="muted top-info-sub">
        <a href="<%= h(ctx) %>/join">Vuoi creare una nuova stanza?</a>
    </div>
    <% } else { %>
    <div class="muted top-info">Crea una nuova stanza oppure entra con un codice stanza esistente.</div>
    <% } %>

    <% if (error != null) { %>
    <div class="err"><b>Errore:</b> <%= h(error) %></div>
    <% } %>

    <% if (inviteMode && hasRoom) { %>
    <!-- INVITO -->
    <div class="section">
        <div class="section-head">
            <p class="section-title">✅ Entra tramite invito</p>
            <p class="section-subtitle">Inserisci il tuo nome per entrare nella stanza.</p>
        </div>

        <form method="post" action="<%= h(ctx) %>/join">
            <input type="hidden" name="action" value="join"/>
            <input type="hidden" name="source" value="invite"/>
            <input type="hidden" name="room" value="<%= h(room) %>"/>

            <label for="name">Nome</label>
            <input id="name" name="name"
                   placeholder="Es. Mario"
                   value="<%= h(nameVal) %>"
                   minlength="2" maxlength="16"
                   pattern="[A-Za-z0-9À-ÖØ-öø-ÿ _-]{2,16}"
                   required autofocus />

            <div class="actions">
                <button type="submit">Entra</button>
            </div>
        </form>
    </div>

    <% } else { %>

    <!-- CREA STANZA -->
    <div class="section">
        <div class="section-head">
            <p class="section-title">① Crea una stanza</p>
            <p class="section-subtitle">Scegli il tuo nome e il numero di giocatori.</p>
        </div>

        <form method="post" action="<%= h(ctx) %>/join">
            <input type="hidden" name="action" value="create"/>

            <div class="row">
                <div class="col">
                    <label for="nameCreate">Nome</label>
                    <input id="nameCreate" name="name"
                           placeholder="Es. Mario"
                           value="<%= h(nameVal) %>"
                           minlength="2" maxlength="16"
                           pattern="[A-Za-z0-9À-ÖØ-öø-ÿ _-]{2,16}"
                           required autofocus />
                </div>

                <div class="col">
                    <label for="players">Numero giocatori</label>
                    <select id="players" name="players" required>
                        <%
                            for (int n = 2; n <= 6; n++) {
                                String sel = String.valueOf(n).equals(playersVal) ? "selected" : "";
                        %>
                        <option value="<%= n %>" <%= sel %>><%= n %></option>
                        <% } %>
                    </select>
                </div>
            </div>

            <div class="actions">
                <button type="submit">Crea stanza</button>
            </div>
        </form>
    </div>

    <div class="divider">Oppure</div>

    <!-- ENTRA MANUALE -->
    <div class="section">
        <div class="section-head">
            <p class="section-title">② Entra in una stanza</p>
            <p class="section-subtitle">Inserisci il codice stanza e il tuo nome.</p>
        </div>

        <form method="post" action="<%= h(ctx) %>/join">
            <input type="hidden" name="action" value="join"/>
            <input type="hidden" name="source" value="manual"/>

            <label for="room">Room</label>
            <input id="room" name="room"
                   value="<%= hasRoom ? h(room) : "" %>"
                   minlength="4" maxlength="8"
                   pattern="[A-Za-z0-9]{4,8}"
                   required />

            <label for="nameJoin">Nome</label>
            <input id="nameJoin" name="name"
                   value="<%= h(nameVal) %>"
                   minlength="2" maxlength="16"
                   pattern="[A-Za-z0-9À-ÖØ-öø-ÿ _-]{2,16}"
                   required />

            <div class="actions">
                <button type="submit">Entra</button>
            </div>
        </form>
    </div>

    <% } %>
</div>
</body>
</html>
