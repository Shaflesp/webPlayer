import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.util.regex.*;
import java.util.*;

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
    private final String YT_CHANNEL_URL = "https://www.googleapis.com/youtube/v3/channels?part=snippet,localizations&key=" + API_KEY + "&id=";

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
        int totalCount = 0;
        String nextPageToken = "";

        do {
            // Si autre page que la première, ajoute le token
            String url = YT_API_URL + playlistId;
            if (!nextPageToken.isEmpty()) {
                url += "&pageToken=" + nextPageToken;
            }

            System.out.println("Fetching Page: " + (nextPageToken.isEmpty() ? "First" : nextPageToken));

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JsonObject json = new Gson().fromJson(response.body(), JsonObject.class);

            if (!json.has("items")) break;

            // On gère les 50 items de batch
            JsonArray items = json.getAsJsonArray("items");

            for (JsonElement item : items) {
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
                SongInfo clean = cleanMetadata(rawTitle, channelTitle, channelId, client);

                boolean success = insertSong(conn, clean.title, clean.artist, videoId);
                if (success) totalCount++;
            }

            if (json.has("nextPageToken")) {
                nextPageToken = json.get("nextPageToken").getAsString();
            } else {
                nextPageToken = null; // Stop the loop
            }

        } while (nextPageToken != null);

        return totalCount;
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

        String channelId = snippet.has("channelId")
            ? snippet.get("channelId").getAsString()
            : "";
        SongInfo clean = cleanMetadata(rawTitle, channelTitle, channelId, client);

        boolean success = insertSong(conn, clean.title, clean.artist, videoId);
        return success ? 1 : 0;
    }

private SongInfo cleanMetadata(String rawTitle, String channelName, String channelId, HttpClient client) {
    // 1. NORMALIZE
    String title = rawTitle.replaceAll("[\u2014\u2013\u2215]", "-") // em-dash, en-dash, division slash
                           .replaceAll("[\\p{Cf}\\p{Co}\\p{Cn}]", "")
                           .trim();

    // Clean Channel
    String cleanChannel = channelName.replaceAll(" - Topic", "")
                                     .replaceAll("(?i)(VEVO|Official|Channel|Music|Records|TV|Audio|Studio)", "")
                                     .trim();

    // Build channel aliases
    java.util.List<String> aliasesList = new java.util.ArrayList<>();
    for (String alias : cleanChannel.split("[/\uFF0F]")) {
        String trimmed = alias.trim();
        if (!trimmed.isEmpty()) aliasesList.add(trimmed);
    }

    // Fetch additional aliases from YouTube API
    if (channelId != null && !channelId.isEmpty()) {
        try {
            java.util.List<String> apiAliases = fetchChannelAliases(channelId, client);
            for (String alias : apiAliases) {
                if (!aliasesList.contains(alias) && !alias.isEmpty()) {
                    aliasesList.add(alias);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch channel aliases: " + e.getMessage());
        }
    }

    String[] channelAliases = aliasesList.toArray(new String[0]);

    // 2. GARBAGE REMOVAL
    String[] garbagePatterns = {
        // === BRACKETS 【】 ===
        "(?i)\u3010[^\u3011]*?\u3011", // Any 【...】

        // === BRACKETS []  ===
        "(?i)\\[[^\\]]*?(official|mv|music video|hd|4k|hq|lyrics?|video|original|clip|officiel|nv).*?\\]",
        "(?i)\\[\\s*NV\\s*\\]",
        "(?i)\\[\\s*CLIP\\s+OFFICIEL\\s*\\]",
        "\\[\\s*\\]",

        // === JAPANESE BRACKETS 「」 ===
        "(?i)\u300C[^\u300D]*?(MV|\uFF2D\uFF36)[^\u300D]*?\u300D", // 「MV」「ＭＶ」

        // === PARENTHESES () ===
        // Official/Video variations
        "(?i)\\(\\s*official\\s+video\\s+remastered\\s*\\)",
        "(?i)\\(\\s*official\\s+music\\s+video\\s*\\)",
        "(?i)\\(\\s*official\\s+video\\s*\\)",
        "(?i)\\(\\s*official\\s+audio\\s*\\)",
        "(?i)\\(\\s*official\\s+mv\\s*\\)",
        "(?i)\\(\\s*music\\s+video\\s*\\)",
        "(?i)\\(\\s*original\\s+song\\s*\\)",
        "(?i)\\(\\s*original\\s+video\\s*\\)",
        "(?i)\\(\\s*lyric\\s*s?\\s*video\\s*\\)",
        "(?i)\\(\\s*lyric\\s*s?\\s*\\)",
        "(?i)\\(\\s*audio\\s*\\)",
        "(?i)\\(\\s*remastered\\s*\\)",
        "(?i)\\(\\s*cover\\s*\\)",
        "(?i)\\(\\s*mv\\s*\\)",
        "(?i)\\(clip\\s+officiel\\)",

        // === FULLWIDTH PARENTHESES （） ===
        "(?i)\uFF08\\s*(official|mv|music video|video).*?\uFF09",

        // === SUFFIXES (end of string) ===
        "(?i)\\s+official\\s+music\\s+video\\s*$",
        "(?i)\\s+music\\s+video\\s*$",
        "(?i)\\s+official\\s+video\\s*$",
        "(?i)\\s+official\\s+audio\\s*$",
        "(?i)\\s+official\\s*$",
        "(?i)\\s+remastered\\s*$",
        "(?i)\\s+mv\\s*$",
        "(?i)\\s+hd\\s*$",

        // === COVER ===
        "(?i)[/\uFF0F]?\\s*cover\\.?\\s*$",
        "(?i)\\s+cover\\.\\s*$",
        "(?i)\\s+cover\\s*$",

        // === JAPANESE ===
        "(?i)\u6B4C\u3044\u307E\u3057\u305F", // 歌いました
        "(?i)\u6B4C\u3063\u3066\u307F\u305F", // 歌ってみた

        // === OTHER ===
        "(?i)\\([^)]*full\\s*album[^)]*\\)",
        "(?i)\\(\\s*mv\\s*pt\\.?\\s*\\d*\\s*\\)",
        "(?i)\\((long|full|short)\\s*ver\\.?\\)",
    };

    for (String pattern : garbagePatterns) {
        title = title.replaceAll(pattern, "");
    }

    // 3. REMOVE PARENTHETICAL TRANSLATIONS (redundant info)
    // Pattern: （English / Artist feat. ...） or (Romanization / Artist feat. ...)
    // These are translations of what's already in the title
    java.util.regex.Pattern transPattern = java.util.regex.Pattern.compile(
        "\\s*[\uFF08(][^)\uFF09]*?/[^)\uFF09]*?(feat|ft)[^)\uFF09]*?[)\uFF09]"
    );
    title = transPattern.matcher(title).replaceAll("").trim();

    // Also remove simple translation blocks: （Something） at end
    java.util.regex.Pattern simpleTransPattern = java.util.regex.Pattern.compile(
        "\\s*\uFF08[^\uFF09]+\uFF09\\s*$"
    );
    title = simpleTransPattern.matcher(title).replaceAll("").trim();

    // 4. PUNCTUATION CLEANUP
    title = title.replaceAll("[\u201C\u201D\u201E]", "\"")
                 .replaceAll("\uFF08\\s*\uFF09", "")
                 .replaceAll("\\(\\s*\\)", "")
                 .replaceAll("\u300E\\s*\u300F", "")
                 .replaceAll("\u3010\\s*\u3011", "")
                 .replaceAll("\\[\\s*\\]", "")
                 .trim();
    title = removeQuotes(title);

    // 5. EXTRACT "FEAT" FROM TITLE (move to artist later)
    java.util.List<String> extractedFeats = new java.util.ArrayList<>();

    // Pattern A: (feat. X), (ft. X), (with X), (w/ X) - with or without space
    java.util.regex.Pattern featParenPattern = java.util.regex.Pattern.compile(
        "(?i)\\s*[\uFF08(]\\s*(feat\\.?|ft\\.?)\\s*([^)\uFF09]+)[)\uFF09]"
    );
    java.util.regex.Matcher featParenMatcher = featParenPattern.matcher(title);
    while (featParenMatcher.find()) {
        extractedFeats.add(featParenMatcher.group(2).trim());
    }
    title = featParenPattern.matcher(title).replaceAll("").trim();

    // Pattern B: "feat. X", "feat.X", "ft. X", "ft.X" at end of string (no parentheses)
    java.util.regex.Pattern featEndPattern = java.util.regex.Pattern.compile(
        "(?i)\\s+(feat\\.?|ft\\.?)\\s*(.+?)\\s*$"
    );
    java.util.regex.Matcher featEndMatcher = featEndPattern.matcher(title);
    if (featEndMatcher.find()) {
        extractedFeats.add(featEndMatcher.group(2).trim());
        title = title.substring(0, featEndMatcher.start()).trim();
    }

    // Combine all extracted feats
    String extractedFeat = String.join(", ", extractedFeats);

    // 6. SPLIT LOGIC
    String artist = "";
    String finalTitle = title;
    boolean splitFound = false;

    // Check if artist part already has feat (e.g., "ぐちり feat.音街ウナ、鏡音レン")
    // If so, don't add more feat later
    boolean artistAlreadyHasFeat = false;

    // PATTERN 0: "Title - Vocaloid VS Vocaloid" -> keep whole thing as title, artist = channel
    // e.g., "ダイダイダイダイダイキライ - 初音ミク VS 重音テト"
    if (title.contains(" - ") && title.toUpperCase().contains(" VS ")) {
        String[] parts = title.split(" - ", 2);
        String part2 = parts.length > 1 ? parts[1].trim() : "";
        if (containsVocaloids(part2) && part2.toUpperCase().contains("VS")) {
            artist = cleanChannel;
            finalTitle = title; // Keep entire title including "- Vocaloid VS Vocaloid"
            splitFound = true;
        }
    }

    // PATTERN A: "Artist feat. X / EnglishTitle - JapaneseTitle"
    // e.g., "ピノキオピー feat. 初音ミク / Non - ノンブレス・オブリージュ"
    // e.g., "ピノキオピー feat. 初音ミク / Apple dot com - アップルドットコム"
    java.util.regex.Pattern slashDashPattern = java.util.regex.Pattern.compile(
        "^(.+?)\\s*/\\s*(.+?)\\s+-\\s+(.+)$"
    );
    java.util.regex.Matcher sdMatcher = slashDashPattern.matcher(title);
    if (sdMatcher.find()) {
        String p1 = sdMatcher.group(1).trim(); // Before slash
        String p2 = sdMatcher.group(2).trim(); // Between slash and dash
        String p3 = sdMatcher.group(3).trim(); // After dash

        // Check if p1 matches channel (it's the artist)
        if (matchesAnyAlias(p1, channelAliases) || p1.toLowerCase().contains("feat")) {
            artist = p1;
            // p2 and p3 are title variants, pick the longer/Japanese one
            finalTitle = p3.length() >= p2.length() ? p3 : p2;
            splitFound = true;
        }
        // Check if it's "Title / Title - Artist" pattern
        else if (matchesAnyAlias(p3, channelAliases)) {
            artist = p3;
            finalTitle = p2.length() >= p1.length() ? p2 : p1;
            splitFound = true;
        }
    }

    // PATTERN B: "Title (Vocaloid) - Artist" (reversed)
    // e.g., "アウターサイエンス (IA) - じん"
    if (!splitFound) {
        java.util.regex.Pattern reversedPattern = java.util.regex.Pattern.compile(
            "^(.+?)\\s*[\uFF08(]([^)\uFF09]+)[)\uFF09]\\s*-\\s*(.+)$"
        );
        java.util.regex.Matcher revMatcher = reversedPattern.matcher(title);
        if (revMatcher.find()) {
            String potentialTitle = revMatcher.group(1).trim();
            String parenContent = revMatcher.group(2).trim();
            String potentialArtist = revMatcher.group(3).trim();

            // If paren contains vocaloid and last part is short -> reversed
            if (isVocaloid(parenContent) && potentialArtist.length() < 30) {
                finalTitle = potentialTitle;
                artist = potentialArtist;
                if (extractedFeat.isEmpty()) extractedFeat = parenContent;
                splitFound = true;
            }
        }
    }

    // PATTERN C: "EnglishTitle / JapaneseTitle - Artist"
    // e.g., "Young Girl A / 少女A - 椎名もた"
    if (!splitFound) {
        java.util.regex.Pattern titleTitleArtist = java.util.regex.Pattern.compile(
            "^(.+?)\\s*/\\s*(.+?)\\s+-\\s+(.+)$"
        );
        java.util.regex.Matcher ttaMatcher = titleTitleArtist.matcher(title);
        if (ttaMatcher.find()) {
            String p1 = ttaMatcher.group(1).trim();
            String p2 = ttaMatcher.group(2).trim();
            String p3 = ttaMatcher.group(3).trim();

            // If p3 matches channel, it's the artist
            if (matchesAnyAlias(p3, channelAliases)) {
                artist = p3;
                finalTitle = p2; // Prefer Japanese title
                splitFound = true;
            }
        }
    }

    // PATTERN D: "Artist / Title - Translation" or "Artist - Title / Translation"
    // e.g., "ぬゆり - ロウワー / Flower - Lower one's eyes"
    if (!splitFound) {
        java.util.regex.Pattern artistTitleTrans = java.util.regex.Pattern.compile(
            "^(.+?)\\s+-\\s+(.+?)\\s*/\\s*.+?\\s+-\\s+.+$"
        );
        java.util.regex.Matcher attMatcher = artistTitleTrans.matcher(title);
        if (attMatcher.find()) {
            String p1 = attMatcher.group(1).trim();
            String p2 = attMatcher.group(2).trim();

            if (matchesAnyAlias(p1, channelAliases)) {
                artist = p1;
                finalTitle = p2;
                splitFound = true;
            }
        }
    }

    // PATTERN E: Three-part "A - B - C"
    if (!splitFound && title.split(" - ").length >= 3) {
        String[] parts = title.split(" - ", 3);
        String p1 = parts[0].trim();
        String p2 = parts[1].trim();
        String p3 = parts[2].trim();

        boolean p1Match = matchesAnyAlias(p1, channelAliases);
        boolean p2Match = matchesAnyAlias(p2, channelAliases);
        boolean p3Match = matchesAnyAlias(p3, channelAliases);

        if (p2Match) {
            artist = p2; finalTitle = p1; splitFound = true;
        } else if (p1Match) {
            artist = p1; finalTitle = p2; splitFound = true;
        } else if (p3Match) {
            artist = p3; finalTitle = p1.length() > p2.length() ? p1 : p2; splitFound = true;
        } else if (containsOnlyVocaloids(p3)) {
            // "Title - Subtitle - Vocaloids" -> artist from channel
            artist = cleanChannel;
            finalTitle = p1;
            if (extractedFeat.isEmpty()) extractedFeat = extractVocaloidsFromString(p3);
            splitFound = true;
        } else {
            artist = p1; finalTitle = p2; splitFound = true;
        }
    }

    // PATTERN F: Two-part "A - B"
    if (!splitFound && title.contains(" - ")) {
        String[] parts = title.split(" - ", 2);
        String part1 = parts[0].trim();
        String part2 = parts[1].trim();

        boolean p1Match = matchesAnyAlias(part1, channelAliases);
        boolean p2Match = matchesAnyAlias(part2, channelAliases);

        // Check if part2 contains ONLY vocaloids (no VS) -> artist is channel
        if (containsOnlyVocaloids(part2)) {
            artist = cleanChannel;
            finalTitle = part1;
            if (extractedFeat.isEmpty()) extractedFeat = extractVocaloidsFromString(part2);
            splitFound = true;
        } else if (p2Match) {
            finalTitle = part1; artist = part2; splitFound = true;
        } else if (p1Match) {
            artist = part1; finalTitle = part2; splitFound = true;
        } else if (part1.toLowerCase().contains("feat")) {
            artist = part1; finalTitle = part2; splitFound = true;
        } else {
            // Default: Artist - Title
            artist = part1; finalTitle = part2; splitFound = true;
        }
    }

    // PATTERN G: Slash only "A / B"
    if (!splitFound && title.contains(" / ")) {
        String[] parts = title.split(" / ", 2);
        String part1 = parts[0].trim();
        String part2 = parts[1].trim();

        boolean p1Match = matchesAnyAlias(part1, channelAliases);
        boolean p2Match = matchesAnyAlias(part2, channelAliases);

        if (p1Match || part1.toLowerCase().contains("feat")) {
            artist = part1; finalTitle = part2;
        } else if (p2Match) {
            artist = part2; finalTitle = part1;
        } else {
            // Default for slash: Title / Artist
            finalTitle = part1; artist = part2;
        }
        splitFound = true;
    }

    // 7. FALLBACK TO CHANNEL
    if (artist.isEmpty()) {
        artist = cleanChannel;
        // Remove channel name from title if present
        for (String alias : channelAliases) {
            if (containsIgnoreCase(finalTitle, alias)) {
                finalTitle = finalTitle.replaceAll("(?i)\\Q" + alias + "\\E", "").trim();
            }
        }
    }

    // Check if artist already has feat info
    artistAlreadyHasFeat = artist.toLowerCase().contains("feat") ||
                           artist.toLowerCase().contains("ft.") ||
                           artist.contains("\uFF06") || // ＆
                           artist.contains("&");

    // 8. APPEND EXTRACTED FEAT (with deduplication)
    if (!extractedFeat.isEmpty() && !artistAlreadyHasFeat) {
        // Normalize and deduplicate feat
        extractedFeat = deduplicateFeat(extractedFeat, artist);

        if (!extractedFeat.isEmpty()) {
            artist = artist + " feat. " + extractedFeat;
        }
    }

    return finalize(finalTitle, artist);
}

// Deduplicate feat string against existing artist
private String deduplicateFeat(String feat, String artist) {
    // Split feat by comma
    String[] featParts = feat.split("[,\u3001]");
    java.util.List<String> unique = new java.util.ArrayList<>();

    for (String part : featParts) {
        String p = part.trim();
        if (p.isEmpty()) continue;

        // Check if already in artist
        if (containsIgnoreCase(artist, p)) continue;

        // Check for case-insensitive duplicates (flower/Flower)
        boolean isDupe = false;
        for (String u : unique) {
            if (u.equalsIgnoreCase(p)) { isDupe = true; break; }
        }
        if (!isDupe) unique.add(p);
    }

    return String.join(", ", unique);
}

// --- FETCH CHANNEL ALIASES FROM YOUTUBE API ---
private java.util.List<String> fetchChannelAliases(String channelId, HttpClient client) throws Exception {
    java.util.List<String> aliases = new java.util.ArrayList<>();

    String url = YT_CHANNEL_URL + channelId;
    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

    JsonObject json = new Gson().fromJson(response.body(), JsonObject.class);

    if (!json.has("items") || json.getAsJsonArray("items").size() == 0) {
        return aliases;
    }

    JsonObject item = json.getAsJsonArray("items").get(0).getAsJsonObject();
    JsonObject snippet = item.getAsJsonObject("snippet");

    if (snippet.has("title")) {
        aliases.add(snippet.get("title").getAsString().trim());
    }

    if (snippet.has("customUrl")) {
        String customUrl = snippet.get("customUrl").getAsString().replaceAll("^@", "").trim();
        aliases.add(customUrl);
    }

    if (item.has("localizations")) {
        JsonObject localizations = item.getAsJsonObject("localizations");
        for (String lang : localizations.keySet()) {
            JsonObject loc = localizations.getAsJsonObject(lang);
            if (loc.has("title")) {
                String locTitle = loc.get("title").getAsString().trim();
                if (!aliases.contains(locTitle)) aliases.add(locTitle);
            }
        }
    }

    java.util.List<String> cleanAliases = new java.util.ArrayList<>();
    for (String alias : aliases) {
        String clean = alias.replaceAll("(?i)(VEVO|Official|Channel|Music|Records|TV|Audio|Studio)", "").trim();
        if (!clean.isEmpty() && !cleanAliases.contains(clean)) cleanAliases.add(clean);
    }

    return cleanAliases;
}

// --- HELPERS ---
private boolean matchesAnyAlias(String text, String[] aliases) {
    for (String alias : aliases) {
        if (alias.length() > 1 && containsIgnoreCase(text, alias)) return true;
    }
    return false;
}

// Base vocaloid names (without variants)
private static final String[] VOCALOID_BASE = {
    "\u521D\u97F3\u30DF\u30AF",  // 初音ミク
    "\u91CD\u97F3\u30C6\u30C8",  // 重音テト
    "\u93E1\u97F3\u30EA\u30F3",  // 鏡音リン
    "\u93E1\u97F3\u30EC\u30F3",  // 鏡音レン
    "\u97F3\u8857\u30A6\u30CA",  // 音街ウナ
    "\u53EF\u4E0D",              // 可不
    "\u661F\u754C",              // 星界
    "GUMI", "IA", "flower", "Flower", "KAITO", "MEIKO",
    "Miku", "Teto", "Rin", "Len", "Kasane Teto", "Hatsune Miku",
    "Kagamine Rin", "Kagamine Len", "Otomachi Una"
};

private boolean isVocaloid(String text) {
    String t = text.trim();
    for (String v : VOCALOID_BASE) {
        if (t.equalsIgnoreCase(v)) return true;
        if (containsIgnoreCase(t, v)) return true;
    }
    return false;
}

private boolean containsVocaloids(String text) {
    for (String v : VOCALOID_BASE) {
        if (containsIgnoreCase(text, v)) return true;
    }
    return false;
}

private boolean containsOnlyVocaloids(String text) {
    if (text.isEmpty()) return false;

    // Special case: if it contains "VS" between vocaloids, it's likely a title element
    // e.g., "初音ミク VS 重音テト" should be kept in title
    if (text.toUpperCase().contains(" VS ")) {
        return false; // Keep as part of title
    }

    String temp = text;

    // Remove vocaloid names (allow variants like カゼヒキβ)
    for (String v : VOCALOID_BASE) {
        temp = temp.replaceAll("(?i)\\Q" + v + "\\E[^\u3001,\\s]*", "");
    }
    // Also match pattern: Japanese word + β/α (for unknown vocaloids)
    temp = temp.replaceAll("[\\p{IsHan}\\p{IsKatakana}\\p{IsHiragana}]+[\u03B1\u03B2]", "");

    // Remove separators: comma, Japanese comma, space
    temp = temp.replaceAll("[,\u3001\\s]+", "").trim();
    return temp.isEmpty();
}

private String extractVocaloidsFromString(String text) {
    // Extract the original vocaloid strings as they appear (preserving β, etc.)
    java.util.List<String> found = new java.util.ArrayList<>();

    // Split by comma or Japanese comma
    String[] parts = text.split("[,\u3001]");
    for (String part : parts) {
        String p = part.trim().replaceAll("(?i)^VS\\s*", "").trim();
        if (!p.isEmpty()) {
            // Check if this part contains a known vocaloid
            for (String v : VOCALOID_BASE) {
                if (containsIgnoreCase(p, v)) {
                    found.add(p); // Add the full part (e.g., "カゼヒキβ")
                    break;
                }
            }
            // Also check for Japanese word + Greek letter pattern
            if (found.isEmpty() || !found.contains(p)) {
                if (p.matches(".*[\u03B1\u03B2]$")) {
                    found.add(p);
                }
            }
        }
    }

    // Deduplicate (case-insensitive for "flower"/"Flower")
    java.util.List<String> deduped = new java.util.ArrayList<>();
    for (String f : found) {
        boolean exists = false;
        for (String d : deduped) {
            if (d.equalsIgnoreCase(f)) { exists = true; break; }
        }
        if (!exists) deduped.add(f);
    }

    return String.join(", ", deduped);
}

private SongInfo finalize(String title, String artist) {
    title = title.trim().replaceAll("^[/\\-:\uFF1A]+|[/\\-:\uFF1A]+$", "").trim();
    artist = artist.trim().replaceAll("^[/\\-:\uFF1A]+|[/\\-:\uFF1A]+$", "").trim();
    title = removeQuotes(title);
    artist = removeQuotes(artist);
    if (title.isBlank()) title = "Unknown Title";
    if (artist.isBlank()) artist = "Unknown Artist";
    return new SongInfo(title, artist);
}

private String removeQuotes(String text) {
    if (text == null) return "";
    String s = text.trim();
    String[][] quotePairs = {
        {"'", "'"},
        {"\"", "\""},
        {"\u300C", "\u300D"},  // 「  」
        {"\u300E", "\u300F"},  // 『  』
        {"\u201C", "\u201D"},  // " "
        {"\u2018", "\u2019"},  // ' '
        {"\uFF62", "\uFF63"}   // ｢ ｣
    };
    for (String[] pair : quotePairs) {
        if (s.startsWith(pair[0]) && s.endsWith(pair[1]) && s.length() > 2) {
            return s.substring(pair[0].length(), s.length() - pair[1].length()).trim();
        }
    }
    return s;
}

private boolean containsIgnoreCase(String src, String what) {
    if (src == null || what == null) return false;
    return src.toLowerCase().contains(what.toLowerCase());
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