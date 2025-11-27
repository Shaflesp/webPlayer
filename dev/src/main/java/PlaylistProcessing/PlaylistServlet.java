package PlaylistProcessing;

import java.io.*;
import java.sql.*;
import com.google.gson.Gson;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import jakarta.servlet.annotation.WebServlet;

import java.util.ArrayList;
import java.util.List;

@WebServlet("/PlaylistServlet")
public class PlaylistServlet extends HttpServlet {

    private final String URL = "jdbc:postgresql://localhost:5432/postgres";
    private final String USER = "postgres";
    private final String PASS = "password";

    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        List<Song> songs = new ArrayList<>();
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        try (Connection conn = DriverManager.getConnection(URL, USER, PASS);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM track")) {

            while (rs.next()) {
                Song s = new Song();
                s.title = rs.getString("title");
                s.artist = rs.getString("artist");
                s.videoId = rs.getString("video_id"); // Fetch the ID
                songs.add(s);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        String json = new Gson().toJson(songs);

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json);
    }
}