package it.gioco31.controller;

import it.gioco31.room.GameRoom;
import it.gioco31.room.RoomRepository;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

@WebServlet("/leave")
public class LeaveServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session != null) {
            String roomId = (String) session.getAttribute("roomId");
            String token  = (String) session.getAttribute("playerToken");

            if (roomId != null && token != null) {
                roomId = RoomRepository.normalizeRoomId(roomId);
                GameRoom room = RoomRepository.get(roomId);
                if (room != null) {
                    room.releaseTokenAndFreeSlot(token);
                    room.touch();
                }
            }
            session.invalidate();
        }
        resp.sendRedirect(req.getContextPath() + "/");
    }
}
