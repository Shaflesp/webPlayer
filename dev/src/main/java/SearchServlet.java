import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.*;
import jakarta.servlet.annotation.*;
import jakarta.servlet.http.*;

import com.google.gson.Gson;

@WebServlet("/SearchServlet")
public class SearchServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    private static final String DB_URL = "jdbc:postgresql://localhost:5432/postgres";
    private static final String DB_USER = "postgres";
    private static final String DB_PASS = "password";

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        String query = request.getParameter("q");

        if (query == null) {
            query = "";
        }

        List<Song> songs = new ArrayList<>();
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            Class.forName("org.postgresql.Driver");
            
            conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
            
            String sql;
            
            if (query.trim().isEmpty()) {
                sql = "SELECT * FROM track ORDER BY id DESC LIMIT 50";
                pstmt = conn.prepareStatement(sql);
            } else {
                sql = "SELECT * FROM track WHERE title ILIKE ? OR artist ILIKE ?";
                pstmt = conn.prepareStatement(sql);
                String searchPattern = "%" + query + "%";
                pstmt.setString(1, searchPattern);
                pstmt.setString(2, searchPattern);
            }

            rs = pstmt.executeQuery();

            while (rs.next()) {
                int id = rs.getInt("id");
                String title = rs.getString("title");
                String artist = rs.getString("artist");
                String videoId = rs.getString("video_id");

                songs.add(new Song(id, title, artist, videoId));
            }

        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"error\": \"" + e.getMessage() + "\"}");
            return;
        } finally {
            // Clean up resources
            try { if (rs != null) rs.close(); } catch (SQLException e) { e.printStackTrace(); }
            try { if (pstmt != null) pstmt.close(); } catch (SQLException e) { e.printStackTrace(); }
            try { if (conn != null) conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }

        // Convert list to JSON and send
        String json = new Gson().toJson(songs);
        PrintWriter out = response.getWriter();
        out.print(json);
        out.flush();
    }
}