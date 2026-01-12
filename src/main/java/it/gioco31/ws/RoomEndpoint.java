package it.gioco31.ws;

import it.gioco31.model.Card;
import it.gioco31.model.GameState;
import it.gioco31.model.Phase;
import it.gioco31.model.Player;
import it.gioco31.room.GameRoom;
import it.gioco31.room.RoomRepository;
import it.gioco31.service.GameLifecycle;

import jakarta.servlet.http.HttpSession;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint(value = "/ws/{roomId}", configurator = HttpSessionConfigurator.class)
public class RoomEndpoint {

    private static final Map<String, Set<Session>> ROOM_SESSIONS = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session ws, EndpointConfig cfg, @PathParam("roomId") String roomId) throws IOException {
        String rid = RoomRepository.normalizeRoomId(roomId);

        GameRoom room = RoomRepository.get(rid);
        if (room == null) { ws.close(close("Room not found")); return; }

        HttpSession http = (HttpSession) cfg.getUserProperties().get("httpSession");
        if (http == null) { ws.close(close("No HTTP session")); return; }

        String token = (String) http.getAttribute("playerToken");
        String sessionRoom = RoomRepository.normalizeRoomId((String) http.getAttribute("roomId"));
        if (token == null || sessionRoom == null || !rid.equals(sessionRoom)) {
            ws.close(close("Not joined"));
            return;
        }

        Integer me = room.indexByToken(token);
        if (me == null) { ws.close(close("Invalid player")); return; }

        ws.getUserProperties().put("token", token);
        ROOM_SESSIONS.computeIfAbsent(rid, k -> ConcurrentHashMap.newKeySet()).add(ws);
        room.touch();
        broadcast(rid, room);
    }

    @OnMessage
    public void onMessage(Session ws, String msg, @PathParam("roomId") String roomId) throws IOException {
        String rid = RoomRepository.normalizeRoomId(roomId);

        GameRoom room = RoomRepository.get(rid);
        if (room == null) return;

        String token = (String) ws.getUserProperties().get("token");
        if (token == null) return;

        Integer me = room.indexByToken(token);
        if (me == null) {
            try { ws.close(close("Invalid player")); } catch (Exception ignore) {}
            return;
        }

        String[] parts = msg.split(":");
        if (parts.length < 2 || !"ACTION".equals(parts[0])) return;

        room.lock().lock();
        try {
            applyAction(room, me, parts);
            room.touch();
        } finally {
            room.lock().unlock();
        }

        broadcast(rid, room);
    }

    @OnClose
    public void onClose(Session ws, @PathParam("roomId") String roomId) {
        String rid = RoomRepository.normalizeRoomId(roomId);

        Set<Session> set = ROOM_SESSIONS.get(rid);
        if (set != null) {
            set.remove(ws);
            if (set.isEmpty()) ROOM_SESSIONS.remove(rid, set);
        }

        ws.getUserProperties().remove("token");
    }

    private void applyAction(GameRoom room, int me, String[] parts) {
        GameState s = room.state();

        String action = parts[1];

        if ("ackNotice".equals(action)) {
            if (parts.length >= 3) {
                try {
                    long id = Long.parseLong(parts[2]);
                    s.clearNoticeForPlayer(me, id);
                } catch (NumberFormatException ignored) {}
            }
            return;
        }

        if ("restartGame".equals(action)) {
            if (s.getPhase() != Phase.GAME_OVER) return;
            if (me != 0) return; // host = player 1
            restartGame(room, s);
            return;
        }

        if (me < 0 || me >= s.getPlayers().size()) return;
        if (s.getPlayers().get(me).isEliminated()) return;

        if (s.getPhase() == Phase.WAITING_FOR_PLAYERS) return;
        if (s.getPhase() == Phase.GAME_OVER) return;
        if (s.getCurrentIndex() != me) return;

        switch (action) {
            case "drawDeck" -> {
                try { room.engine().drawPendingFromDeck(s); }
                catch (RuntimeException ex) { s.setNoticeForPlayer(me, ex.getMessage()); }
            }
            case "drawDiscard" -> {
                try { room.engine().drawPendingFromDiscard(s); }
                catch (RuntimeException ex) { s.setNoticeForPlayer(me, ex.getMessage()); }
            }

            case "keep" -> {
                if (parts.length >= 3) {
                    try {
                        int idx = Integer.parseInt(parts[2]);

                        boolean made31 = room.engine().takeAndDiscard(s, idx);
                        if (made31) applyInstantThirtyOneWin(room, s, me);

                    } catch (NumberFormatException ignored) {
                    } catch (RuntimeException ex) {
                        s.setNoticeForPlayer(me, ex.getMessage());
                    }
                }
            }

            case "reject" -> {
                try { room.engine().rejectDraw(s); }
                catch (RuntimeException ex) { s.setNoticeForPlayer(me, ex.getMessage()); }
            }

            case "knock" -> {
                try { room.engine().knock(s); }
                catch (RuntimeException ex) { s.setNoticeForPlayer(me, ex.getMessage()); }
            }

            default -> { /* ignore */ }
        }
    }

    private void restartGame(GameRoom room, GameState s) {
        GameLifecycle.resetMatchState(s);
        int joined = GameLifecycle.preparePlayersForNewMatch(s);

        if (joined < 2) {
            s.setPhase(Phase.WAITING_FOR_PLAYERS);
            return;
        }

        s.setDealerIndex(0);
        room.engine().startRound(s);
    }

    private void applyInstantThirtyOneWin(GameRoom room, GameState s, int winnerIndex) {
        if (s.getPhase() == Phase.WAITING_FOR_PLAYERS || s.getPhase() == Phase.GAME_OVER) return;

        s.setPendingDraw(null);

        int eliminatedNow = 0;

        for (int i = 0; i < s.getPlayers().size(); i++) {
            if (i == winnerIndex) continue;

            Player other = s.getPlayers().get(i);
            if (other.isEliminated()) continue;

            int newLives = Math.max(0, other.getLives() - 1);
            other.setLives(newLives);

            if (newLives <= 0) {
                other.setEliminated(true);
                other.getHand().clear();
                eliminatedNow++;
            }
        }

        int alive = 0;
        for (Player p : s.getPlayers()) if (!p.isEliminated()) alive++;

        if (alive <= 1) {
            s.setWinnerIndex(winnerIndex);
            s.setPhase(Phase.GAME_OVER);
            s.clearAllNotices();
            return;
        }

        String winnerName = safeName(s, winnerIndex);
        String msg = (eliminatedNow > 0)
                ? ("31! " + winnerName + " vince il round: tutti gli altri perdono 1 vita. (" + eliminatedNow + " eliminato/i)")
                : ("31! " + winnerName + " vince il round: tutti gli altri perdono 1 vita.");

        for (int i = 0; i < s.getPlayers().size(); i++) {
            s.setNoticeForPlayer(i, msg);
        }

        room.engine().startRound(s);
    }

    private String safeName(GameState s, int idx) {
        if (idx < 0 || idx >= s.getPlayers().size()) return "Player";
        String n = s.getPlayers().get(idx).getName();
        if (n == null || n.isBlank()) return "Player " + (idx + 1);
        return n;
    }

    private void broadcast(String rid, GameRoom room) throws IOException {
        Set<Session> set = ROOM_SESSIONS.get(rid);
        if (set == null || set.isEmpty()) return;

        var snapshot = new java.util.ArrayList<>(set);
        var toRemove = new java.util.ArrayList<Session>();

        for (Session ws : snapshot) {
            if (ws == null || !ws.isOpen()) {
                toRemove.add(ws);
                continue;
            }

            String token = (String) ws.getUserProperties().get("token");
            if (token == null) {
                toRemove.add(ws);
                continue;
            }

            Integer viewer = room.indexByToken(token);
            if (viewer == null) {
                toRemove.add(ws);
                try { ws.close(close("Invalid player")); } catch (Exception ignore) {}
                continue;
            }

            try {
                sendState(ws, room, viewer);
            } catch (Exception e) {
                toRemove.add(ws);
                try { ws.close(close("IO error")); } catch (Exception ignore) {}
            }
        }

        for (Session ws : toRemove) {
            if (ws != null) set.remove(ws);
        }

        if (set.isEmpty()) {
            ROOM_SESSIONS.remove(rid, set);
        }
    }

    private void sendState(Session ws, GameRoom room, int viewerIndex) throws IOException {
        String json;
        room.lock().lock();
        try {
            json = buildStateJson(room.state(), viewerIndex);
        } finally {
            room.lock().unlock();
        }
        ws.getBasicRemote().sendText(json);
    }

    private String buildStateJson(GameState s, int viewerIndex) {
        boolean viewerEliminated = false;
        if (viewerIndex >= 0 && viewerIndex < s.getPlayers().size()) {
            Player v = s.getPlayers().get(viewerIndex);
            viewerEliminated = (v != null && v.isEliminated());
        }

        boolean inPlay = (s.getPhase() == Phase.PLAYING || s.getPhase() == Phase.KNOCK_CALLED);

        int handViewIndex = (viewerEliminated && inPlay) ? s.getCurrentIndex() : viewerIndex;
        if (handViewIndex < 0 || handViewIndex >= s.getPlayers().size()) handViewIndex = viewerIndex;
        if (handViewIndex < 0 || handViewIndex >= s.getPlayers().size()) handViewIndex = 0;

        Player handOwner = s.getPlayers().get(handViewIndex);

        Card viewPending = null;
        if (inPlay) {
            if (viewerEliminated) viewPending = s.getPendingDraw();
            else if (viewerIndex == s.getCurrentIndex()) viewPending = s.getPendingDraw();
        }

        Player.BestSuitScore best = handOwner.bestSameSuitScore();

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"phase\":").append(jsonStr(String.valueOf(s.getPhase()))).append(",");
        sb.append("\"viewerIndex\":").append(viewerIndex).append(",");
        sb.append("\"currentIndex\":").append(s.getCurrentIndex()).append(",");
        sb.append("\"winnerIndex\":").append(s.getWinnerIndex() != null ? s.getWinnerIndex() : "null").append(",");
        sb.append("\"deckSize\":").append(s.getDeck() != null ? s.getDeck().size() : 0).append(",");
        sb.append("\"discardTop\":").append(cardJson(s.getDiscard().peek())).append(",");

        sb.append("\"viewerEliminated\":").append(viewerEliminated).append(",");
        sb.append("\"handViewIndex\":").append(handViewIndex).append(",");

        sb.append("\"viewHand\":[");
        for (int h = 0; h < handOwner.getHand().size(); h++) {
            if (h > 0) sb.append(",");
            sb.append(cardJson(handOwner.getHand().get(h)));
        }
        sb.append("],");
        sb.append("\"viewBestSuitValue\":").append(best.getValue()).append(",");
        sb.append("\"viewBestSuitLabel\":").append(best.getSuit() == null ? "null" : jsonStr(best.getSuit().label())).append(",");
        sb.append("\"viewPending\":").append(cardJson(viewPending)).append(",");

        sb.append("\"players\":[");
        for (int i = 0; i < s.getPlayers().size(); i++) {
            Player p = s.getPlayers().get(i);
            if (i > 0) sb.append(",");

            sb.append("{");
            sb.append("\"index\":").append(i).append(",");
            sb.append("\"name\":").append(jsonStr(p.getName())).append(",");
            sb.append("\"lives\":").append(p.getLives()).append(",");
            sb.append("\"eliminated\":").append(p.isEliminated()).append(",");
            sb.append("\"joined\":").append(p.isJoined()).append(",");
            sb.append("\"cardCount\":").append(p.getHand().size()).append(",");

            Card pendingForViewer = (!viewerEliminated
                    && i == viewerIndex
                    && viewerIndex == s.getCurrentIndex())
                    ? s.getPendingDraw()
                    : null;
            sb.append("\"pendingDraw\":").append(cardJson(pendingForViewer)).append(",");

            if (i == viewerIndex) {
                GameState.Notice n = s.getNoticeForPlayer(viewerIndex);
                sb.append("\"noticeId\":").append(n != null ? n.getId() : "null").append(",");
                sb.append("\"noticeMsg\":").append(n != null ? jsonStr(n.getMessage()) : "null");
            } else {
                sb.append("\"noticeId\":null,");
                sb.append("\"noticeMsg\":null");
            }

            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String cardJson(Card c) {
        if (c == null) return "null";
        return "{\"suit\":" + jsonStr(String.valueOf(c.suit())) +
                ",\"rank\":" + jsonStr(String.valueOf(c.rank())) +
                ",\"value\":" + c.value() +
                ",\"label\":" + jsonStr(c.label()) + "}";
    }

    private String jsonStr(String x) {
        if (x == null) return "null";
        StringBuilder out = new StringBuilder(x.length() + 8);
        out.append('"');
        for (int i = 0; i < x.length(); i++) {
            char ch = x.charAt(i);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '"'  -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private CloseReason close(String msg) {
        return new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, msg);
    }
}
