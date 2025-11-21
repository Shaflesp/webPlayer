import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import jakarta.servlet.annotation.WebServlet;

@WebServlet("/AddServlet")
public class AddServlet extends HttpServlet {

    private final String DB_URL = "jdbc:postgresql://localhost:5432/postgres";
    private final String USER = "postgres";
    private final String PASS = "password";

    private final String API_KEY = "AIzaSyAqYI4sgLREL4KoLfImNr6kAA17GNPUsqo";
    private final String YT_API_URL = "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&maxResults=50&key=" + API_KEY + "&playlistId=";
    private final String YT_VIDEO_URL = "https://www.googleapis.com/youtube/v3/videos?part=snippet&key=" + API_KEY + "&id=";

    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setCharacterEncoding("UTF-8");
        BufferedReader reader = request.getReader();
        Gson gson = new Gson();
        RequestData reqData = gson.fromJson(reader, RequestData.class);

        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {

                int addedCount = 0;

                if (reqData.playlistId != null && !reqData.playlistId.isEmpty()) {
                    addedCount = processPlaylist(reqData.playlistId, conn);
                }
                else if (reqData.videoId != null) {
                    addedCount = processSingleVideo(reqData.videoId, conn);
                }

                response.getWriter().write("Processed. Added " + addedCount + " new songs.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(500);
            response.getWriter().write("Error: " + e.getMessage());
        }
    }

    private int processPlaylist(String playlistId, Connection conn) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String url = YT_API_URL + playlistId;
        int count = 0;

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        JsonObject json = new Gson().fromJson(response.body(), JsonObject.class);

        if (!json.has("items")) return 0;

        JsonArray items = json.getAsJsonArray("items");

        for (JsonElement item : items) {
            JsonObject snippet = item.getAsJsonObject().getAsJsonObject("snippet");
            String rawTitle = snippet.get("title").getAsString();
            String channelTitle = snippet.get("videoOwnerChannelTitle").getAsString();
            String videoId = snippet.getAsJsonObject("resourceId").get("videoId").getAsString();

            SongInfo clean = cleanMetadata(rawTitle, channelTitle);

            boolean success = insertSong(conn, clean.title, clean.artist, videoId);
            if (success) count++;
        }
        return count;
    }

    private boolean insertSong(Connection conn, String title, String artist, String videoId) {
        String sql = "INSERT INTO playlist (title, artist, video_id) VALUES (?, ?, ?) ON CONFLICT (video_id) DO NOTHING";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, title);
            stmt.setString(2, artist);
            stmt.setString(3, videoId);
            int rows = stmt.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    private int processSingleVideo(String videoId, Connection conn) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String url = YT_VIDEO_URL + videoId;

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        JsonObject json = new Gson().fromJson(response.body(), JsonObject.class);

        if (!json.has("items") || json.getAsJsonArray("items").size() == 0) {
            return 0; // Video not found or private
        }

        JsonObject item = json.getAsJsonArray("items").get(0).getAsJsonObject();
        JsonObject snippet = item.getAsJsonObject("snippet");

        String rawTitle = snippet.get("title").getAsString();
        String channelTitle = snippet.get("channelTitle").getAsString(); // Note: Field name is slightly different for Videos vs Playlists

        SongInfo clean = cleanMetadata(rawTitle, channelTitle);

        boolean success = insertSong(conn, clean.title, clean.artist, videoId);
        return success ? 1 : 0;
    }

    private SongInfo cleanMetadata(String rawTitle, String channelName) {
        // 1. Clean the Channel Name (Artist)
        String artist = channelName.replace(" - Topic", "").trim();
        String title = rawTitle;

        // 2. Define Garbage Regex Patterns (Case Insensitive)
        String[] garbagePatterns = {
        "(?i)\\[.*?\\]",
        "(?i)【.*?】",
        "(?i)\\((official\\s*)?video\\)",
        "(?i)\\((official\\s*)?music\\s*video\\)",
                "(?i)official\\s*music\\s*video",
        "(?i)\\((official\\s*)?mv\\)",
        "(?i)\\(mv\\)",
        "(?i)official\\s*audio",
        "(?i)official\\s*mv",
        "(?i)mv\\s*pt\\.?\\d*",
                "(?i)\\(\\s*mv\\s*pt\\.?\\s*\\d*\\s*\\)",
//        "(?i)cover\\.?\\s*" + artist,
//        "(?i)cover\\.?",
//        "(?i)self\\s*cover",
        "(?i)歌いました",
        "(?i)歌ってみた",
        "(?i)\\(long\\s*ver\\.?\\)",
        "(?i)\\(full\\s*ver\\.?\\)"
        };

        // Apply Garbage Removal
        for (String pattern : garbagePatterns) {
            title = title.replaceAll(pattern, "");
        }

        // 4. Smart Splitter (Artist - Title)
        String[] separators = {" - ", " / ", " : ", " X ", " x "};
        boolean splitFound = false;

        for (String sep : separators) {
            String effectiveSep = sep;
            if(sep.equals("『") && !title.contains("『")) continue;
            if(sep.equals("『")) effectiveSep = "『";

            if (title.contains(effectiveSep)) {
                String[] parts = title.split(effectiveSep, 2);
                String part1 = parts[0].trim();
                String part2 = parts[1].trim();

                if (containsIgnoreCase(part2, artist)) {
                    title = part1;
                    artist = part2;
                    splitFound = true;
                }
                else if (containsIgnoreCase(part1, artist)) {
                    artist = part1;
                    title = part2;
                    splitFound = true;
                }

                if (sep.equals("『")) title = title.replace("』", "");

                if (splitFound) break;
            }
        }

        String regexArtist = "(?i)\\Q" + artist + "\\E";

        if (title.toLowerCase().contains(artist.toLowerCase())) {
            title = title.replaceAll(regexArtist, "");
        }

        title = title.trim();
        title = title.replaceAll("\\(\\s*\\)", "").trim();
        title = title.replaceAll("^[\\/\\-\\:]+|[\\/\\-\\:]+$", "").trim();

        title = removeQuotes(title);
        title = removeQuotes(title);

    //      Azari
    //    if (title.isBlank()) title = "Unknown Title";
    //    if (artist.isBlank()) artist = "Unknown Artist";

        return new SongInfo(title, artist);
    }

    private String removeQuotes(String text) {
        if (text == null) return "";
        String s = text.trim();
        // Remove 'single' or "double" or 「Japanese」 quotes
        if ((s.startsWith("'") && s.endsWith("'")) ||
            (s.startsWith("\"") && s.endsWith("\"")) ||
            (s.startsWith("「") && s.endsWith("」"))) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private boolean containsIgnoreCase(String src, String what) {
        return src.toLowerCase().contains(what.toLowerCase());
    }
    private boolean startsWithIgnoreCase(String src, String what) {
        return src.toLowerCase().startsWith(what.toLowerCase());
    }

    class SongInfo {
        String title;
        String artist;

        SongInfo(String t, String a) { this.title = t; this.artist = a; }
    }
    class RequestData {
        String title;
        String artist;
        String videoId;
        String playlistId; // New field
    }
}