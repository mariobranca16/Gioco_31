package it.gioco31.controller;

import it.gioco31.room.RoomRepository;

import it.gioco31.util.UrlUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@WebServlet("/room")
public class RoomServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        HttpSession session = req.getSession(false);

        String roomId = RoomRepository.normalizeRoomId(req.getParameter("room"));
        if (roomId == null || roomId.isBlank()) {
            roomId = session != null ? RoomRepository.normalizeRoomId((String) session.getAttribute("roomId")) : null;
        }

        if (roomId == null || roomId.isBlank()) {
            resp.sendRedirect(req.getContextPath() + "/join");
            return;
        }

        if (!RoomRepository.exists(roomId)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Room non trovata");
            return;
        }

        String token = session != null ? (String) session.getAttribute("playerToken") : null;
        String sessionRoom = session != null ? RoomRepository.normalizeRoomId((String) session.getAttribute("roomId")) : null;

        if (token == null || sessionRoom == null || !roomId.equals(sessionRoom)) {
            resp.sendRedirect(req.getContextPath() + "/join?room=" + UrlUtil.enc(roomId));
            return;
        }
        
        req.setAttribute("roomId", roomId);
        req.getRequestDispatcher("/WEB-INF/jsp/room.jsp").forward(req, resp);
    }
}
