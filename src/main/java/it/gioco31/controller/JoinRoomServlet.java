package it.gioco31.controller;

import it.gioco31.GameConstants;
import it.gioco31.model.Phase;
import it.gioco31.model.Player;
import it.gioco31.room.GameRoom;
import it.gioco31.room.RoomRepository;
import it.gioco31.util.UrlUtil;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

import java.io.IOException;
import java.util.UUID;

@WebServlet("/join")
public class JoinRoomServlet extends HttpServlet {

    private static int parseSlots(String raw) {
        int v = 4;
        if (raw != null) {
            try { v = Integer.parseInt(raw.trim()); } catch (NumberFormatException ignored) {}
        }
        if (v < GameConstants.MIN_PLAYERS) v = GameConstants.MIN_PLAYERS;
        if (v > GameConstants.MAX_PLAYERS) v = GameConstants.MAX_PLAYERS;
        return v;
    }

    private static void forwardJoinWithError(HttpServletRequest req, HttpServletResponse resp, String msg)
            throws ServletException, IOException {
        req.setAttribute("error", msg);
        req.getRequestDispatcher("/WEB-INF/jsp/join.jsp").forward(req, resp);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.getRequestDispatcher("/WEB-INF/jsp/join.jsp").forward(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        String action = req.getParameter("action");
        if (action == null || action.isBlank()) action = "join";

        String nameRaw = req.getParameter("name");
        if (nameRaw == null || nameRaw.isBlank()) {
            forwardJoinWithError(req, resp, "Inserisci un nome valido.");
            return;
        }

        HttpSession session = req.getSession(true);

        String roomId;
        GameRoom room;

        if ("create".equalsIgnoreCase(action)) {
            int slots = parseSlots(req.getParameter("players"));
            room = RoomRepository.createNewRoom(slots);
            roomId = RoomRepository.normalizeRoomId(room.roomId());

        } else if ("join".equalsIgnoreCase(action)) {

            roomId = RoomRepository.normalizeRoomId(req.getParameter("room"));
            if (!isValidRoomId(roomId)) {
                forwardJoinWithError(req, resp, "Codice stanza non valido.");
                return;
            }

            room = RoomRepository.get(roomId);
            if (room == null) {
                forwardJoinWithError(req, resp, "Room non trovata.");
                return;
            }

        } else {
            forwardJoinWithError(req, resp, "Azione non valida.");
            return;
        }

        String existingToken = (String) session.getAttribute("playerToken");
        String existingRoom = RoomRepository.normalizeRoomId((String) session.getAttribute("roomId"));

        if (existingToken != null && roomId.equals(existingRoom)) {
            Integer idx = room.indexByToken(existingToken);
            if (idx != null) {
                resp.sendRedirect(req.getContextPath() + "/room?room=" + UrlUtil.enc(roomId));
                return;
            } else {
                session.removeAttribute("playerToken");
                session.removeAttribute("roomId");
            }
        }

        if (room.state().getPhase() != Phase.WAITING_FOR_PLAYERS) {
            forwardJoinWithError(req, resp, "Partita già iniziata: non è possibile entrare ora.");
            return;
        }

        String token = UUID.randomUUID().toString();
        Integer idx;

        room.lock().lock();
        try {
            String candidate = nameRaw.trim();

            for (Player p : room.state().getPlayers()) {
                if (p.isJoined() && p.getName() != null && p.getName().equalsIgnoreCase(candidate)) {
                    forwardJoinWithError(req, resp, "Nome già utilizzato nella room.");
                    return;
                }
            }

            idx = findFreeSlot(room);
            if (idx < 0) {
                forwardJoinWithError(req, resp, "Room piena.");
                return;
            }

            Player me = room.state().getPlayers().get(idx);
            try {
                me.setName(candidate);
            } catch (IllegalArgumentException ex) {
                forwardJoinWithError(req, resp, ex.getMessage());
                return;
            }

            me.setJoined(true);
            room.bindToken(token, idx);
            room.touch();
        } finally {
            room.lock().unlock();
        }

        session.setAttribute("playerToken", token);
        session.setAttribute("roomId", roomId);

        resp.sendRedirect(req.getContextPath() + "/room?room=" + UrlUtil.enc(roomId));
    }

    private static int findFreeSlot(GameRoom room) {
        for (int i = 0; i < room.state().getPlayers().size(); i++) {
            Player p = room.state().getPlayers().get(i);
            if (!p.isJoined()) return i;
        }
        return -1;
    }

    private static boolean isValidRoomId(String roomId) {
        String id = RoomRepository.normalizeRoomId(roomId);
        if (id == null) return false;
        int len = id.length();
        if (len < GameConstants.ROOM_ID_MIN_LEN || len > GameConstants.ROOM_ID_MAX_LEN) return false;
        return id.matches("[A-Z0-9]+");
    }
}
