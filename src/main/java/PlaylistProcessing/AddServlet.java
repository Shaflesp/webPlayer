package PlaylistProcessing;

import MetaDataJannitor.Processor.GarbagePattern;
import Utilities.KeyManager;
import com.google.gson.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.sql.*;

import jakarta.servlet.http.*;
import jakarta.servlet.annotation.WebServlet;

import MetaDataJannitor.*;


@WebServlet("/AddServlet")
public class AddServlet extends HttpServlet {

    private final String DB_URL = "jdbc:postgresql://localhost:5432/postgres";
    private final String DB_USER = "postgres";
    private final String DB_PASS = "password";

    public static final String API_KEY = KeyManager.get("youtubeDataV3");
    private final String YT_API_URL = "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&maxResults=50&key=" + API_KEY + "&playlistId=";
    private final String YT_VIDEO_URL = "https://www.googleapis.com/youtube/v3/videos?part=snippet&key=" + API_KEY + "&id=";

    private MetaDataCleaner metaDataJannitor;

    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setCharacterEncoding("UTF-8");
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");
        response.setBufferSize(0);

        PrintWriter out = response.getWriter();
        Connection conn = null;

        try {
            BufferedReader reader = request.getReader();
            Gson gson = new Gson();
            RequestData reqData = gson.fromJson(reader, RequestData.class);

            Class.forName("org.postgresql.Driver");
            conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);

            int addedCount;

            if (reqData.playlistId != null && !reqData.playlistId.isEmpty()) {
                addedCount = processPlaylist(reqData.playlistId, conn, (current, total) -> {
                    out.println("PROGRESS:" + current + "/" + total);
                    out.flush();
                });
                out.println("DONE:Playlist Processed. Added " + addedCount + " songs.");
            }
            else if (reqData.videoId != null) {
                addedCount = processSingleVideo(reqData.videoId, conn);
                if(addedCount > 0) out.println("DONE:Song Added");
                else out.println("ERROR:Could not add song (Private or Invalid)");
            }
        } catch (Exception e) {
            e.printStackTrace();
            out.println("ERROR:" + e.getMessage());
        } finally {
            try { if(conn != null) conn.close(); } catch(SQLException sqle) {sqle.printStackTrace();}
        }
    }

    private int processPlaylist(String playlistId, Connection conn,ProgressObserver observer) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        int totalCount = 0;
        int processedCount = 0;
        int totalItemsInPlaylist = 0;
        String nextPageToken = "";

        do {
            // Si autre page que la première, ajoute le token
            String url = YT_API_URL + playlistId;
            if (!nextPageToken.isEmpty()) {
                url += "&pageToken=" + nextPageToken;
            }

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JsonObject json = new Gson().fromJson(response.body(), JsonObject.class);

            if (!json.has("items")) break;

            if (totalItemsInPlaylist == 0 && json.has("pageInfo")) {
                totalItemsInPlaylist = json.getAsJsonObject("pageInfo").get("totalResults").getAsInt();
            }

            // On gère les 50 items du batch
            JsonArray items = json.getAsJsonArray("items");

            for (JsonElement item : items) {
                processedCount++;
                if (observer != null && totalItemsInPlaylist > 0) {
                    observer.onProgress(processedCount, totalItemsInPlaylist);
                }

                JsonObject snippet = item.getAsJsonObject().getAsJsonObject("snippet");

                if (snippet.get("title").getAsString().equals("Private video") ||
                    snippet.get("title").getAsString().equals("Deleted video")) {
                    continue;
                }

                String rawTitle = snippet.get("title").getAsString();
                String channelTitle = snippet.get("videoOwnerChannelTitle").getAsString();
                String videoId = snippet.getAsJsonObject("resourceId").get("videoId").getAsString();

                String channelId = snippet.has("videoOwnerChannelId")
                    ? snippet.get("videoOwnerChannelId").getAsString()
                    : "";
                metaDataJannitor=new MetaDataCleaner(rawTitle, channelTitle, channelId, client);
                Song clean = metaDataJannitor.getCleanedSong();

                boolean success = insertSong(conn, clean.title,rawTitle, clean.artist, videoId);
                if (success) totalCount++;
            }

            if (json.has("nextPageToken")) {
                nextPageToken = json.get("nextPageToken").getAsString();
            } else {
                nextPageToken = null; // Stop the loop
            }

        } while (nextPageToken != null);
        GarbagePattern.printUsage();
        return totalCount;
    }

    private boolean insertSong(Connection conn, String title,String raw_title, String artist, String videoId) {
        String sql = "INSERT INTO track (title, raw_title,artist, video_id) VALUES (?,?, ?, ?) ON CONFLICT (video_id) DO NOTHING";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, title);
            stmt.setString(2, raw_title);
            stmt.setString(3, artist);
            stmt.setString(4, videoId);
            int rows = stmt.executeUpdate();
            return rows > 0;
        } catch (SQLException sqle) {
            sqle.printStackTrace();
            return false;
        }
    }

    private int processSingleVideo(String videoId, Connection conn) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String url = YT_VIDEO_URL + videoId;

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        JsonObject json = new Gson().fromJson(response.body(), JsonObject.class);

        if (!json.has("items") || json.getAsJsonArray("items").isEmpty()) {
            return 0; // Video not found or private
        }

        JsonObject item = json.getAsJsonArray("items").get(0).getAsJsonObject();
        JsonObject snippet = item.getAsJsonObject("snippet");

        String rawTitle = snippet.get("title").getAsString();
        String channelTitle = snippet.get("channelTitle").getAsString(); // Note: Field name is slightly different for Videos vs Playlists

        String channelId = snippet.has("channelId")
            ? snippet.get("channelId").getAsString()
            : "";
        metaDataJannitor=new MetaDataCleaner(rawTitle, channelTitle, channelId, client);
        Song clean = metaDataJannitor.getCleanedSong();

        boolean success = insertSong(conn, clean.title,rawTitle, clean.artist, videoId);
        return success ? 1 : 0;
    }

    static class RequestData {
        String title;
        String artist;
        String videoId;
        String playlistId;
    }
}