import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.http.*;
import jakarta.servlet.annotation.WebServlet;

@WebServlet("/AddServlet")
public class AddServlet extends HttpServlet {

    private final String DB_URL = "jdbc:postgresql://localhost:5432/postgres";
    private final String DB_USER = "postgres";
    private final String DB_PASS = "password";

    //KeyManager key=new KeyManager()
    private final String API_KEY = KeyManager.get("youtubeDataV3");
    private final String YT_API_URL = "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&maxResults=50&key=" + API_KEY + "&playlistId=";
    private final String YT_VIDEO_URL = "https://www.googleapis.com/youtube/v3/videos?part=snippet&key=" + API_KEY + "&id=";
    private final String YT_CHANNEL_URL = "https://www.googleapis.com/youtube/v3/channels?part=snippet,localizations&key=" + API_KEY + "&id=";

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
                Song clean = cleanMetadata(rawTitle, channelTitle, channelId, client);

                boolean success = insertSong(conn, clean.title,rawTitle, clean.artist, videoId);
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
        Song clean = cleanMetadata(rawTitle, channelTitle, channelId, client);

        boolean success = insertSong(conn, clean.title,rawTitle, clean.artist, videoId);
        return success ? 1 : 0;
    }

private Song cleanMetadata(String rawTitle, String channelName, String channelId, HttpClient client) {
    String title = rawTitle;

    // === 1. NORMALIZE ===
    title = title.replaceAll("[\u2014\u2013\u2215]", "-")
                 .replaceAll("[\u3000\u00A0\u2002-\u200B]", " ")
                 .replaceAll("[\u2018\u2019\u0060\u00B4]", "'")
                 .replaceAll("[\u201C\u201D\u201E\u00AB\u00BB]", "\"")
                 .replaceAll("[\\p{Cf}\\p{Co}\\p{Cn}]", "")
                 .replace("\uFF20", "@")
                 .replace("\uFF0F","/")
                 .replace("//", "/")
                 .replace("￤", " - ")
                 .trim();

    // === 2. BUILD CHANNEL ALIASES ===
    String cleanChannel = sanitizeChannelName(channelName);

    java.util.List<String> aliasesList = new java.util.ArrayList<>();
    aliasesList.add(cleanChannel);
    for (String a : cleanChannel.split("[/\uFF0F]")) {
        String t = a.trim();
        if (!t.isEmpty()) aliasesList.add(t);
    }

    if (channelId != null && !channelId.isEmpty() && client != null) {
        try {
            java.util.List<String> apiAliases = fetchChannelAliases(channelId, client);
            for (String alias : apiAliases) {
                String sanitized = sanitizeChannelName(alias);
                boolean exists = false;
                for (String existing : aliasesList) {
                    if (existing.equalsIgnoreCase(alias)) { exists = true; break; }
                }
                if (!exists && !sanitized.isEmpty()) aliasesList.add(sanitized);
            }
        } catch (Exception e) { /* Ignore */ }
    }
    String[] channelAliases = aliasesList.toArray(new String[0]);

    java.util.regex.Matcher mBracketStart = java.util.regex.Pattern.compile("^【(.+?)】\\s*(.+)$").matcher(title);
    if (mBracketStart.find()) {
        String content = mBracketStart.group(1).trim();
        String restOfTitle = mBracketStart.group(2).trim();
        StringBuilder extractedFeat = new StringBuilder();

        String[] cleanNames = content.replaceAll("(?i)\\s*(?:[×x&]|soft)\\s*", ", ").split(", ");
        for (String vocal : cleanNames) {
            vocal = vocal.trim();
            if (isVocaloid(vocal)) {
                if (extractedFeat.isEmpty()) {
                    extractedFeat = new StringBuilder(vocal);
                } else {
                    extractedFeat.append(", ").append(vocal);
                }
            }
        }
        title=restOfTitle+((extractedFeat.isEmpty()) ? "" : " (feat. " + extractedFeat + ")");
    }

    // === 3. GARBAGE REMOVAL ===
    String[] patterns = {
        "[\u2190-\u21FF]",
            "[\u2727]",
        "\u3010[^\u3011]*\u3011",
            "[◤◢]",
            "(?i)[\u300C\u300E][^\u300D\u300F]*?(nightcore|sped\\s*up|mv|official)[^\u300D\u300F]*[\u300D\u300F]",
            "(?i)\\[[^\\]]*?(official|mv|music|video|hd|4k|hq|lyrics?|original|clip|officiel|nv|nightcore|sped\\s*up|speed\\s*up)[^\\]]*\\]",
        "\\[\\s*\\]",
        "(?i)\u300C[^\u300D]*?(MV|\uFF2D\uFF36)[^\u300D]*\u300D",
        // Parentheses
        "(?i)\\([^)]*official\\s*audio[^)]*\\)",
           "(?i)\\([^)]*official\\s*visuali[sz]er[^)]*\\)",
        "(?i)\\([^)]*official[^)]*video[^)]*\\)",
            "(?i)\\([^)]*original[^)]*song[^)]*\\)",
        "(?i)\\([^)]*music\\s*video[^)]*\\)",
        "(?i)\\([^)]*theme\\s*song[^)]*\\)",
        "(?i)\\([^)]*full\\s*album[^)]*\\)",
            "(?i)\\(\\s*lyrics?\\s*\\)",
        "(?i)\\([^)]*lyrics?\\s*video[^)]*\\)",
        "(?i)\\([^)]*clip\\s*officiel[^)]*\\)",
            "(?i)\\([^)]*nightcore[^)]*\\)",
        "(?i)\\(\\s*official\\s*\\)",
        "(?i)\\(\\s*audio\\s*\\)",
        "(?i)\\(\\s*mv\\s*\\)",
            "(?i)\\(\\s*hq\\s*\\)",
        "(?i)\\(\\s*MV\\s*Pt\\.?\\s*\\d*\\s*\\)",
        "(?i)\\(\\s*(long|short|full)\\s+ver\\.?\\s*\\)",
        "(?i)\uFF08[^\uFF09]*?(official|mv|music|video)[^\uFF09]*\uFF09",
        "(?i)\\S*?(MV|ＭＶ)(?=[\\u300C\\u300D\\u300E\\u300F\\(\\[\\{\\s]|$)",
            "(?i)\\s+MV(?=[\\s\u300E\u300C]|$)",
            "(?i)\\[(Drumstep|Dubstep|House|Trap|DnB|Electro|Glitch Hop|Future Bass|Nightcore|Switching Vocals|sped up)\\]",
            "(?i)\\((Drumstep|Dubstep|House|Trap|DnB|Electro|Glitch Hop|Future Bass|Nightcore)\\)",
        "(?i)\\[(Monstercat[^\\]]*)\\]",
        "(?i)\\[NCS( Release)?\\]",
            "(?i)\\[CeVIO AI\\]",
        // Suffixes
            "(?i)\\s*with\\s*translation$",
            "(?i)\\s*・\\s*MUSIC\\s*VIDEO$",
        "(?i)\\s*•\\s*MUSIC\\s*VIDEO$",
        "(?i)\\s*MUSIC\\s*VIDEO(?=[\uFF08(\\[])",
        "(?i)\\s*MUSIC\\s*VIDEO$",
        "(?i)\\s*OFFICIAL\\s*VIDEO(?=[\uFF08(\\[])",
        "(?i)\\s*OFFICIAL\\s*VIDEO$",
        "(?i)\\s*official\\s*audio$",
        "(?i)\\s*official$",
        "(?i)\\s*remastered$",
        "(?i)\\s*mv$",
        "(?i)\\s*hd$",
            "(?i)\\+\\s*mp3",
            "(?i)\\+\\s*dl",
            "(?i)\\s*hq$",
            "(?i)\\s*\\(cover\\)$",
        "(?i)\\s*cover$",
            "(?i)\\s*full\\s*cover$",
            "(?i)\\s*full\\s*cover(?=\\s*-)",
            "(?i)[\\(\\[]\\s*self\\s*-?\\s*cover\\s*[\\)\\]]",
            "(?i)\\s*self\\s*-?\\s*cover$",
        "(?i)lyrics",
        "(?i)SV",
            "(?i)SV.*$",
            "(?i)nightcore",
        // Japanese
        "\u6B4C\u3044\u307E\u3057\u305F",
        "\u6B4C\u3063\u3066\u307F\u305F",
    };

    String prev;
    do {
        prev = title;
        for (String p : patterns) {
            title = title.replaceAll(p, "").trim();
        }
    } while (!title.equals(prev));

    // === 4. CLEANUP BLOCKS & PUNCTUATION ===

    title = title.replaceAll("(?i)([^\\s\\u3000a-zA-Z0-9\\[\\(\uFF08\u3010])(feat\\.?|ft\\.?|with|w/|Vo[\\.．]?)", "$1 $2");
    title = title.replaceAll("\\s*[\uFF08(][^)\uFF09]*?/[^)\uFF09]*?(?i)(feat|ft)[^)\uFF09]*?[)\uFF09]", "").trim();
    title = title.replaceAll("\\s*\uFF08[^\uFF09]+\uFF09\\s*$", "").trim();

    // Pattern 1: Comma separator ", English - translation"
    // e.g., "ハチ - 砂の惑星 feat.初音ミク , HACHI - DUNE ft.Miku Hatsune"
    java.util.regex.Pattern commaPattern = java.util.regex.Pattern.compile("^(.+?\\s*-\\s*.+?)\\s*,\\s*([^,]+\\s*-\\s*.+)$");
    java.util.regex.Matcher commaMatcher = commaPattern.matcher(title);

    if (commaMatcher.find()) {
        String part1 = commaMatcher.group(1).trim();
        String part2 = commaMatcher.group(2).trim();

        boolean p1HasJp = part1.matches(".*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}].*");
        boolean p2HasJp = part2.matches(".*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}].*");
        if (p1HasJp && !p2HasJp) {
            title = part1;
        }
    }

    // Pattern 2: Slash separator "/ English - translation"
    java.util.regex.Pattern slashPattern = java.util.regex.Pattern.compile("^(.+?)\\s*/\\s*([^/]+)\\s*-\\s*.+$");
    java.util.regex.Matcher slashMatcher = slashPattern.matcher(title);

    if (slashMatcher.find()) {
        String part1 = slashMatcher.group(1).trim(); // Avant le slash
        String part2 = slashMatcher.group(2).trim(); // Après le slash (avant le tiret)

        boolean p1HasJp = part1.matches(".*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}].*");
        boolean p2HasJp = part2.matches(".*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}].*");
        if (p1HasJp && !p2HasJp) {
            title = part1;
        }
    }

    title = title.replaceAll("[\u201C\u201D\u201E]", "\"")
                 .replaceAll("\uFF08\\s*\uFF09", "")
                 .replaceAll("\\(\\s*\\)", "")
                 .replaceAll("\u300E\\s*\u300F", "")
                 .replaceAll("\u3010\\s*\u3011", "")
                 .replaceAll("\\[\\s*\\]", "")
                 .replaceAll("\\s+", " ")
                 .trim();
    title = title.replaceAll("([\u3002\u300D\u300F])\\s*-", "$1 - ")
                .replaceAll("(\\S)([\\(\uFF08])", "$1 $2")
                .replaceAll("(\\S)([\\u300C\\u300E])", "$1 $2")
                .replaceAll("\\|+", "-");
    title = removeQuotes(title);

    // === 5. EXTRACT FEAT (Early Extraction) ===
    java.util.List<String> featList = new java.util.ArrayList<>();

    java.util.regex.Pattern pFeatParen = java.util.regex.Pattern.compile("(?i)\\s*[\uFF08(\\[]\\s*(feat\\.?|ft\\.?|with|w/|Vo[\\.．]?)[\\s:]*\\s*([^)\uFF09\\]]+)[)\uFF09\\]]");
    java.util.regex.Matcher mFeatParen = pFeatParen.matcher(title);
    while (mFeatParen.find()) featList.add(mFeatParen.group(2).trim());
    title = title.replaceAll("(?i)\\s*[\uFF08(\\[]\\s*(?:feat\\.?|ft\\.?|with|w/|Vo[\\.．]?)[\\s:]*\\s*[^)\uFF09\\]]+[)\uFF09\\]]", "").trim();

    java.util.regex.Pattern pVo = java.util.regex.Pattern.compile("(?i)Vo[\\.．]?[\\s:]+(.+)$");
    java.util.regex.Matcher mVo = pVo.matcher(title);
    if (mVo.find()) {
        featList.add(mVo.group(1).trim());
        title = title.substring(0, mVo.start()).trim();
    }

//    Pattern p0e = Pattern.compile("(?i)^(.+?)\\s+(feat\\.?|ft\\.?|with)\\s*(.+?)\\s+(.*)$");
//    java.util.regex.Matcher m0e = p0e.matcher(title);
//    if (m0e.find()) { //Moe Moe Kyun <3
//        String pA = m0e.group(1).trim();
//        String pV = m0e.group(3).trim();
//        String pT = m0e.group(4).trim();
//
//        featList.add(pV);
//        title = (pA+ pT).trim();
//    }

    java.util.regex.Pattern pVer = java.util.regex.Pattern.compile("(?i)(?:[\\uFF08(\\[]\\s*(?:([^)\\uFF09\\]]+?)\\s+ver\\.?|ver\\.?\\s+([^)\\uFF09\\]]+?))\\s*[)\\uFF09\\]]|(?:^|\\s)ver\\.?\\s+(.+)$)");
    java.util.regex.Matcher mVer = pVer.matcher(title);
    while (mVer.find()) {
        String potentialArtist = null;

        if (mVer.group(1) != null) potentialArtist = mVer.group(1);
        else if (mVer.group(2) != null) potentialArtist = mVer.group(2);
        else if (mVer.group(3) != null) potentialArtist = mVer.group(3);

        if (potentialArtist != null) {
            potentialArtist = potentialArtist.trim();
            java.util.regex.Matcher mSplit = java.util.regex.Pattern.compile("^(.+?)\\s*[\\(\\uFF08](.+?)[\\)\\uFF09](.*)$").matcher(potentialArtist);

            if (mSplit.find()) {
                featList.add(mSplit.group(1).trim());
                //featList.add(mSplit.group(2).trim()); // Alias

                //trailing part if exists
                String trailing = mSplit.group(3).trim();
                if (!trailing.isEmpty()) {
                    String cleanTrailing = trailing.replaceAll("(?i)^(?:feat\\.?|ft\\.?|with|w/|Vo[\\.．]?|&)\\s*", "").trim();
                    if (!cleanTrailing.isEmpty()) {featList.add(cleanTrailing);}
                }
            } else {featList.add(potentialArtist);}
        }
    }

    title = pVer.matcher(title).replaceAll("").trim();

    // 5b. Feat before Slash "feat. X /" (Fix for Apple dot com)
    java.util.regex.Pattern pFeatSlash = java.util.regex.Pattern.compile("(?i)\\s+(feat|ft)[\\.\\s:]*\\s*(.+?)\\s*(?=/)");
    java.util.regex.Matcher mFeatSlash = pFeatSlash.matcher(title);
    if (mFeatSlash.find()) {
        featList.add(mFeatSlash.group(2).trim());
        title = mFeatSlash.replaceFirst("").trim();
    }

    // 5c. Feat at End
    java.util.regex.Pattern pFeatEnd = java.util.regex.Pattern.compile("(?i)\\s+(feat|ft|with)[\\.\\s:]*\\s*((?:(?!\\s+-\\s+).)+)\\s*$");
    java.util.regex.Matcher mFeatEnd = pFeatEnd.matcher(title);
    if (mFeatEnd.find()){
        if (mFeatEnd.group(1).equalsIgnoreCase("with")&& !isVocaloid(mFeatEnd.group(2).trim())){} //skip
        else {
            featList.add(mFeatEnd.group(2).trim());
            title = title.substring(0, mFeatEnd.start()).trim();
        }
    }

    // 5d. Vocaloid after slash
    if (title.contains("/")) {
        int slashIdx = title.lastIndexOf("/");
        String afterSlash = title.substring(slashIdx + 1).trim();
        if (afterSlash.length() < 20 && isVocaloid(afterSlash)) {
            featList.add(afterSlash);
            title = title.substring(0, slashIdx).trim();
        }
    }

    //5e the metadata skipper
    java.util.regex.Pattern pImplicit = java.util.regex.Pattern.compile("\\s*\\(([^)]+)\\)[\\s-]*$");
    java.util.regex.Matcher mImplicit = pImplicit.matcher(title);
    if (mImplicit.find()) {
        String content = mImplicit.group(1).trim();
        boolean looksLikeList = (content.contains(",") || content.contains("&") || content.contains("×") || content.contains(" x "));
        boolean isMetadata = content.matches("(?i).*(remix|mix|ver\\.|edit|ost|soundtrack|theme|cover|video|audio|lyrics).*");
        boolean isYear = content.matches("^\\d{4}$");

        if (looksLikeList && !isMetadata && !isYear) {
            featList.add(content);
            title = title.substring(0, mImplicit.start()).trim();
        }
    }

//    java.util.regex.Pattern pRemix = java.util.regex.Pattern.compile("(?i)[\\(\\[]\\s*([^)\\]\\(]+?)\\s+(?:Remix|Mix|Flip|Bootleg|Edit|Rmx)\\s*[\\)\\]]");
//    java.util.regex.Matcher mRemix = pRemix.matcher(title);
//    StringBuffer sbRemix = new StringBuffer();
//
//    while (mRemix.find()) {
//        String remixer = mRemix.group(1).trim();
//        if (matchesAnyAlias(remixer, channelAliases)) {
//             featList.add(remixer);
//             mRemix.appendReplacement(sbRemix, ""); // Remove
//        } else {
//             mRemix.appendReplacement(sbRemix, "$0"); //Keep
//        }
//    }
//    mRemix.appendTail(sbRemix);
//    title = sbRemix.toString().trim();
//    title = title.replaceAll("\\s{2,}", " ");

    String extractedFeat = String.join(", ", featList);
    boolean hasExtractedFeat = !extractedFeat.isEmpty();

    // === 6. SPLIT ARTIST / TITLE ===
    String artist = "";
    String finalTitle = title.trim();
    boolean found = false;

    // Pattern 0d: "Vocaloid (Title / Vocaloid) - Title" -> Real artist is channel
    if (!found) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)\\s*/\\s*(.+?)\\s*\\((.+?)\\s*/\\s*(.+?)\\)$").matcher(title);
        if (m.find()) {
            String beforeSlash = m.group(1).trim();
            String afterSlash = m.group(2).trim();
            String vocal = m.group(4).trim();

            // If afterSlash is a vocaloid AND inParen2 references that same vocaloid
            if (isVocaloid(afterSlash) && (containsIgnoreCase(vocal, afterSlash) || isVocaloid(vocal))) {
                artist = cleanChannel;
                finalTitle = beforeSlash;
                if (extractedFeat.isEmpty()) {
                    extractedFeat = afterSlash;
                }
                found = true;
            }
        }
    }

    // Pattern 0: Brackets "Artist『Title』"
    if (!found) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)\\s*[\u300E\u300C](.+?)[\u300F\u300D](.*)$").matcher(title);
        if (m.find()) {
            String pA = m.group(1).trim();
            String pT = m.group(2).trim();
            if (matchesAnyAlias(pA, channelAliases) || pA.length() < 20) {
                artist = pA; finalTitle = pT; found = true;
            }
        }
    }

    // Pattern 0b & 0c (Suffix/Prefix)
    if (!found && !title.contains(" - ") && !title.contains(" / ")) {
        for (String alias : channelAliases) {
            int lastSpace = title.lastIndexOf(' ');
            if (lastSpace != -1) {
                String lastWord = title.substring(lastSpace + 1);
                if (lastWord.equalsIgnoreCase(alias)) {
                    String before = title.substring(0, lastSpace).replaceAll("(?i)\\s*Cover\\.?\\s*$", "").trim();
                    if (!before.isEmpty()) { artist = alias; finalTitle = before; found = true; break;}
                }
            }
            int firstSpace = title.indexOf(' ');
            if (lastSpace != -1){
                String firstWord = title.substring(0,firstSpace);
                if (firstWord.equalsIgnoreCase(alias)){
                    String after= title.substring(alias.length()).replaceAll("^[\u300E\u300C\u300F\u300D'\"\\s]+", "").trim();
                    if (!after.isEmpty()) { artist = alias; finalTitle = after; found = true; break; }
                }
            }
        }
    }

    // Pattern 1: "A - B VS C"
    if (!found && title.contains(" - ") && title.toUpperCase().contains("VS")) {
        String t = title;
        int paren = t.indexOf("(");
        if (paren > 0) t = t.substring(0, paren).trim();

        String after = t.substring(t.indexOf(" - ") + 3).trim();
        if (isVocaloid(after.substring(0,after.indexOf(' ')))
                && isVocaloid(after.substring(after.lastIndexOf(' ')))) {
            extractedFeat=extractedFeat+", "+extractVocaloidsFromString(after.substring(0,after.indexOf(' '))+","+after.substring(after.lastIndexOf(' ')));
            artist = cleanChannel;
            finalTitle = t;
            found = true;
        }
    }


    // Pattern 2: Reversed "(Vocaloid) - Producer"
    if (!found) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)\\s*[\uFF08(]([^)\uFF09]+)[)\uFF09]\\s*-\\s*([^-]+)$").matcher(title);
        if (m.find()) {
            String t = m.group(1).trim();
            String v = m.group(2).trim();
            String a = m.group(3).trim();
            if (isVocaloid(v) && a.length() < 30) {
                finalTitle = t;
                artist = matchesAnyAlias(a,channelAliases) ? a : cleanChannel;
                if (extractedFeat.isEmpty()) extractedFeat = v;
                found = true;
            }
        }
    }

    // Pattern 3: "Artist feat. X / Trans - Title" (Non-breath fix)
    if (!found) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)\\s*/\\s*(.+?)\\s+-\\s+(.+)$").matcher(title);
        if (m.find()) {
            String p1 = m.group(1).trim(), p2 = m.group(2).trim(), p3 = m.group(3).trim();
            if (isVocaloid(p2) && p2.length() < 15 && !p2.contains(":")) {} //skip
            else if (matchesAnyAlias(p1, channelAliases) || p1.toLowerCase().contains("feat") || hasExtractedFeat) {
                artist = p1;
                finalTitle = p3.length() >= p2.length() ? p3 : p2;
                found = true;
            }
            else if (matchesAnyAlias(p3, channelAliases)) {
                artist = p3;
                finalTitle = p2.length() >= p1.length() ? p2 : p1;
                found = true;
            }
        }
    }

    // Pattern 4: "Artist - Title / Trans" (Apple dot com fix)
    if (!found) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)\\s+-\\s+(.+?)\\s*/\\s*(.+)$").matcher(title);
        if (m.find()) {
            String p1 = m.group(1).trim(), p2 = m.group(2).trim(), p3 = m.group(3).trim();

            if (matchesAnyAlias(p1, channelAliases) || hasExtractedFeat) {
                artist = matchesAnyAlias(p1,channelAliases) ? p1 :cleanChannel;
                if (p3.contains(":") || p3.split("\\s+").length > 3) {
                    finalTitle = p2 + " / " + p3;
                } else {
                    finalTitle = p2;
                }
                found = true;
            }
        }
    }

    // Pattern 5: Three-part "A - B - C"
    if (!found && title.split(" - ").length >= 3) {
        String[] parts = title.split(" - ", 3);
        String p1 = parts[0].trim(), p2 = parts[1].trim(), p3 = parts[2].trim();
        if (p2.matches("(?i)^(?:track|no\\.?|#)?\\s*\\d{1,3}$")) {
            artist = matchesAnyAlias(p1, channelAliases) ? p1 : cleanChannel;
            finalTitle = p3;
            found = true;
        }
        else if (matchesAnyAlias(p2, channelAliases)) { artist = p2; finalTitle = p1; found = true; }
        else if (matchesAnyAlias(p1, channelAliases)) { artist = p1; finalTitle = p2; found = true; }
        else if (matchesAnyAlias(p3, channelAliases)) { artist = p3; finalTitle = p1; found = true; }
        else if (containsOnlyVocaloids(p3)) { artist = cleanChannel; finalTitle = p1;
            if(extractedFeat.isEmpty()) extractedFeat = extractVocaloidsFromString(p3); found = true; }
        else { artist = cleanChannel; finalTitle = p2; found = true; }
    }

    // Pattern 6: Two-part " - "
     if (!found && title.contains(" - ")) {
        String[] parts = title.split(" - ", 2);
        String p1 = parts[0].trim(), p2 = parts[1].trim();

        boolean shouldSkip = false;
        if (p1.contains(" / ")) {
            String afterSlash = p1.substring(p1.lastIndexOf(" / ") + 3).trim();
            //6a "Title / Vocaloid - Artist"
            if (isVocaloid(afterSlash) && afterSlash.length() < 15 && !afterSlash.contains(":")) {
                String realTitle = p1.substring(0, p1.lastIndexOf(" / ")).trim();
                finalTitle = realTitle;
                artist = matchesAnyAlias(p2,channelAliases) ? p2 : cleanChannel;
                if (extractedFeat.isEmpty()) {extractedFeat = afterSlash;}
                found = true;
                shouldSkip = true;
            }
        }
        if (p2.matches("(?i)^(Normal|Hard|Easy|Instrumental|Off Vocal|Karaoke|Remix|Mix|Original Mix|Extended|Radio Edit|OST|Original Soundtrack)(?:[\\s\\u3000]*[\\(\\[\\uFF08\\u3010].*)?$")) {
            shouldSkip = true;
        }
        if (!shouldSkip) {
            if (containsOnlyVocaloids(p2)) {
                artist = cleanChannel;
                finalTitle = p1;
                if(extractedFeat.isEmpty()) extractedFeat = extractVocaloidsFromString(p2);
            }
            else if (matchesAnyAlias(p2, channelAliases)) { finalTitle = p1; artist = p2; }
            else if (matchesAnyAlias(p1, channelAliases)) { artist = p1; finalTitle = p2; }
            else if (p1.toLowerCase().contains("feat")) { artist = p1; finalTitle = p2; }
            else if (hasExtractedFeat && p1.length() < 20) { artist = p1; finalTitle = p2; }
            else { artist = p1; finalTitle = p2; }
            found = true;
        }
     }

     //Pattern 6b Title -Vocaloid
    if (!found && title.contains("-")) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)\\s*-\\s*([^\\s]+)").matcher(title);

        if (m.find()) {
            String beforeDash = m.group(1).trim();
            String afterDash = m.group(2).trim();

            if (isVocaloid(afterDash)) {
                finalTitle = beforeDash;
                artist = cleanChannel;
                if (extractedFeat.isEmpty()) {extractedFeat = afterDash;}
                found = true;
            }else if (isVocaloid(beforeDash)) {
                 finalTitle = afterDash;
                 artist = cleanChannel;
                 if (extractedFeat.isEmpty()) {extractedFeat = beforeDash;}
                 found = true;
            }
        }
    }

    // Pattern 6c: Slash with Vocaloid "Title / Vocaloid"
    if (!found && title.contains(" / ") && !title.contains(" - ")) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)\\s*/\\s*(.+)$").matcher(title);
        if (m.find()) {
            String beforeSlash = m.group(1).trim();
            String afterSlashRaw = m.group(2).trim();

            String potentialVocaloid = afterSlashRaw;
            String suffix = "";

            java.util.regex.Matcher mSuffix = java.util.regex.Pattern.compile("^(.*?)(\\s*[\\(\\[\\uFF08\\u3010][^)\\]\\uFF09\\u3011]+[)\\]\\uFF09\\u3011])$").matcher(afterSlashRaw);
            if (mSuffix.find()) {
                potentialVocaloid = mSuffix.group(1).trim();
                suffix = mSuffix.group(2);
            }

            // 2. Validate: Is the remaining part a Vocaloid or List?
            boolean isExactVoc = isVocaloid(potentialVocaloid);
            boolean isStrictList = containsOnlyVocaloids(potentialVocaloid);
            boolean isLooseList = !isStrictList && potentialVocaloid.matches(".*[,&、・].*") && isVocaloid(potentialVocaloid);

            if (isExactVoc || isStrictList || isLooseList) {
                finalTitle = beforeSlash + suffix;
                artist = cleanChannel;
                if (extractedFeat.isEmpty()) {extractedFeat = potentialVocaloid;}
                found = true;
            }
        }
    }

    // Pattern 7: Slash
    if (!found && title.contains(" / ")) {
        String[] parts = title.split(" / ", 2);
        String p1 = parts[0].trim(), p2 = parts[1].trim();
        String p2Suffix = "";
        String p2Clean = p2;

        java.util.regex.Matcher mSuffix = java.util.regex.Pattern.compile("^(.+?)\\s+(\\[.+?\\])$").matcher(p2);
        if (mSuffix.find()) {
            p2Clean = mSuffix.group(1).trim();
            p2Suffix = " " + mSuffix.group(2).trim();
        }
        if (matchesAnyAlias(p1, channelAliases) || p1.toLowerCase().contains("feat")) { artist = p1; finalTitle = p2; }
        else if (matchesAnyAlias(p2Clean, channelAliases)) { artist = p2Clean; finalTitle = p1+p2Suffix; }
        else { finalTitle = p1+p2Suffix; artist = p2Clean; }
        found = true;
    }

    //Pattern 7b: Tight Slash "Title/Artist" (No spaces)
    if (!found && title.contains("/") && !title.contains(" / ")) {
        int slashIdx = title.indexOf("/");
        String p1 = title.substring(0, slashIdx).trim();
        String p2 = title.substring(slashIdx + 1).trim();

        boolean matchChannel = matchesAnyAlias(p2, channelAliases);
        boolean looksLikeArtistList = p2.matches("(?i).*\\s+(&|x|with|feat\\.?|ft\\.?)\\s+.*");
        boolean isVoc = isVocaloid(p2);
        if (matchChannel || looksLikeArtistList || isVoc) {
            finalTitle = p1;
            artist = p2;
            found = true;
        }
    }

    // === 8. FALLBACK ===
    boolean channelIsTopic = channelName.matches("(?i).*\\s-\\sTopic$");
    boolean extractedMatchesChannel = matchesAnyAlias(artist, channelAliases);
    if (found && channelIsTopic && !extractedMatchesChannel) {
        artist = cleanChannel;
        finalTitle = title;
    }
    if (artist.isEmpty()) {artist = cleanChannel;}

    // === 9. Co Artists ===
    String separatorRegex = "(?i)[\\s\\u00A0]+[✦x×✕ｘ&][\\s\\u00A0]+|[\\s\\u00A0]*[×][\\s\\u00A0]*";
    boolean isExactKnownGroup = false;
    for (String alias : channelAliases) {
        if (artist.equalsIgnoreCase(alias)) {
            isExactKnownGroup = true;
            break;
        }
    }
    if (!isExactKnownGroup) {
        if (artist.matches(".*(?:" + separatorRegex +").*")) {
            artist = artist.replaceAll(separatorRegex, ", ");
        }
    }
    String checkBase = artist.split("(?i)\\s+(feat\\.?|ft\\.|with)")[0];
    if (checkBase.contains(",") || checkBase.contains("/")) {
        java.util.regex.Matcher mSplit = java.util.regex.Pattern.compile("^(.+?)(?:\\s+(?:feat\\.?|ft\\.|with)\\s+(.+))?$").matcher(artist);
        if (mSplit.find()) {
            String mainPart = mSplit.group(1);
            String existingFeatPart = mSplit.group(2);

            String[] splitArtists = mainPart.split(",");
            String keptMainArtists = "";
            java.util.List<String> movedToFeat = new java.util.ArrayList<>();

            int i=0;
            for (String splitArtist : splitArtists) {
                String a = splitArtist.trim();
                i++;
                if (i!=1 && i==splitArtists.length && !matchesAnyAlias(a,channelAliases)){
                    keptMainArtists=splitArtists[0];
                    movedToFeat.clear();
                    movedToFeat.addAll(List.of(splitArtists));
                } else if (matchesAnyAlias(a, channelAliases)) {
                    keptMainArtists = a;
                } else {
                    movedToFeat.add(a);
                }
            }

            if (!movedToFeat.isEmpty() || existingFeatPart != null) {
                artist = keptMainArtists;
                if (existingFeatPart != null) {movedToFeat.add(existingFeatPart.trim());}
                if (!extractedFeat.isEmpty()) {movedToFeat.add(extractedFeat);}
                extractedFeat = String.join(", ", movedToFeat);
            }
        }
    }

    // === 10. REDUNDANCY CHECK ===
    String baseArtist = artist.split("(?i)\\s*feat\\.?")[0].trim();
    for (String alias : channelAliases) {
        if (alias.length() > 1) {
            finalTitle = finalTitle.replaceAll("(?i)^\\Q" + alias + "\\E\\s*[\u300E\u300C'\"]*\\s*", "").trim();
            finalTitle = finalTitle.replaceAll("(?i)\\s*[\u300F\u300D'\"]*\\Q" + alias + "\\E\\s*$", "").trim();
        }
    }
    if (baseArtist.length() > 1) {
        finalTitle = finalTitle.replaceAll("(?i)^\\Q" + baseArtist + "\\E\\s*[\u300E\u300C'\"]*\\s*", "").trim();
        finalTitle = finalTitle.replaceAll("(?i)\\s*[\u300F\u300D'\"]*\\Q" + baseArtist + "\\E\\s*$", "").trim();
    }

    if (!matchesAnyAlias(artist, channelAliases)) {
        String cleannedBaseArtist = baseArtist.replaceAll("[\u300E\u300C][^\u300F\u300D]+[\u300F\u300D]", "").trim();
        cleannedBaseArtist = cleannedBaseArtist.replaceAll("\\s{2,}", " ").trim();
        String cleanedArtist = artist.replaceAll("[\u300E\u300C][^\u300F\u300D]+[\u300F\u300D]", "").trim();
        cleanedArtist = cleanedArtist.replaceAll("\\s{2,}", " ").trim();

        if (matchesAnyAlias(cleannedBaseArtist, channelAliases)) {
            baseArtist= cleannedBaseArtist;
            artist = cleanedArtist;
        }
    }
    //Check if artist is just vocaloids
    if (containsOnlyVocaloids(baseArtist)) {
        artist= Vocaloid.normalize(baseArtist);
    }

    // === 11. APPEND FEAT ===
    if (!extractedFeat.isEmpty()) {
        extractedFeat = extractedFeat.replaceAll("\\s*[&、・•×]\\s*", ", ");

        //deduplicate voicebanks
        if (isVocaloid(artist) && artist.matches("^[\\x00-\\x7F]+$")) {
            String canonicalArtist = Vocaloid.normalize(artist);
            java.util.List<String> tempFeats = new java.util.ArrayList<>();
            boolean swapped = false;

            for (String f : extractedFeat.split(",")) {
                String ft = f.trim();
                if (ft.isEmpty()) continue;
                if (!swapped && isVocaloid(ft) && !ft.matches("^[\\x00-\\x7F]+$")
                        && Vocaloid.normalize(ft).equalsIgnoreCase(canonicalArtist)) {
                    artist = ft;
                    swapped = true;
                } else {tempFeats.add(ft);}
            }
            if (swapped) {
                extractedFeat = String.join(", ", tempFeats);
            }
        }

        String existingFeats = "";
        if (artist.toLowerCase().contains("feat")
                || artist.toLowerCase().contains("ft.")
                || artist.toLowerCase().contains("feat.")) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?i)(?:feat\\.?|ft\\.)\\s+(.+)$");
            java.util.regex.Matcher m = p.matcher(artist);
            if (m.find()) {
                existingFeats = m.group(1).trim();
            }
            artist = baseArtist;
        }

        // Merge existing feats with new feats
        String allFeats = existingFeats.isEmpty() ? extractedFeat : existingFeats + ", " + extractedFeat;

        allFeats = deduplicateFeat(allFeats, artist);
        if (!allFeats.isEmpty()) {
            artist = artist + " feat. " + allFeats;
        }
    }

    return finalize(finalTitle, artist);
}

private Song finalize(String title, String artist) {
    title = title.trim().replaceAll("^[/\\-:\uFF1A]+|[/\\-:\uFF1A]+$", "").trim();
    artist = artist.trim().replaceAll("^[/\\-:\uFF1A]+|[/\\-:\uFF1A]+$", "").trim();
    title = removeQuotes(title);
    artist = removeQuotes(artist);

    // Remove dangling brackets
    if (title.matches(".*[\u300D\u300F'\"]$") && !title.matches("^[\u300C\u300E'\"].*")) {
         title = title.replaceAll("[\u300D\u300F'\"]+$", "").trim();
    }
    if (title.matches("^[\u300C\u300E'\"].*") && !title.matches(".*[\u300D\u300F'\"]$")) {
         title = title.replaceAll("^[\u300C\u300E'\"]+", "").trim();
    }

    title=removeOrphanedQuotes(title);
    //Azari
//    if (title.isBlank()) title = "Unknown Title";
//    if (artist.isBlank()) artist = "Unknown Artist";
    return new Song(title, artist);
}

private String sanitizeChannelName(String name) {
    if (name == null) return "";
    String cleaned= name.replaceAll(" - Topic", "")
               .replaceAll(" - \u30c8\u30d4\u30c3\u30c3\u30af", "")
               .replaceAll(" - \u30c8\u30d4\u30c3\u30af", "")
               .replaceAll(" - \u4e3b\u984c", "")
               .replaceAll(" - \u4e3b\u9898", "")
               .replaceAll(" - Channel", "")
               .replaceAll(" - \u30c1\u30e3\u30f3\u30cd\u30eb", "")
                .replaceAll("(?i)\\s+private channel$", "")
                .replaceAll("(?i)\\s+Channel$", "");
    cleaned = cleaned.replaceAll("(?i)[\\s_-]*(VEVO|Official|Music|Records|TV|Audio|Studio|YouTube)$", "");
    return cleaned;
    }

private String removeOrphanedQuotes(String text) {
    if (text == null) return "";
    if (!text.contains("\u300E") && text.contains("\u300F")) {
        text = text.replace("\u300F", " ");
    }else if (text.contains("\u300E") && !text.contains("\u300F")) {
        text = text.replace("\u300E", " ");
    }
    if (!text.contains("\u300C") && text.contains("\u300D")) {
        text = text.replace("\u300D", " ");
    }else if (text.contains("\u300C") && !text.contains("\u300D")) {
        text = text.replace("\u300C", " ");
    }

    text = text.replaceAll("'", "").replaceAll("\"","");
    return text;
}

private String removeQuotes(String text) {
    if (text == null) return "";
    String s = text.trim();
    String[][] pairs = {{"'","'"}, {"\"","\""}, {"\u300C","\u300D"}, {"\u300E","\u300F"}};
    for (String[] p : pairs) {
        if (s.startsWith(p[0]) && s.endsWith(p[1]) && s.length() >= 2) {
            return s.substring(p[0].length(), s.length() - p[1].length()).trim();
        }
    }
    return s;
}

private java.util.List<String> fetchChannelAliases(String channelId, HttpClient client) throws Exception {
    java.util.List<String> aliases = new java.util.ArrayList<>();
    String url = YT_CHANNEL_URL + channelId;
    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).build();
    HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
    JsonObject json = new Gson().fromJson(res.body(), JsonObject.class);

    if (!json.has("items") || json.getAsJsonArray("items").isEmpty()) return aliases;

    JsonObject item = json.getAsJsonArray("items").get(0).getAsJsonObject();
    JsonObject snippet = item.getAsJsonObject("snippet");

    if (snippet.has("title")) aliases.add(snippet.get("title").getAsString().trim());
    if (snippet.has("customUrl")) {
        aliases.add(snippet.get("customUrl").getAsString().replaceAll("^@", "").trim());
    }
    if (item.has("localizations")) {
        for (String lang : item.getAsJsonObject("localizations").keySet()) {
            JsonObject loc = item.getAsJsonObject("localizations").getAsJsonObject(lang);
            if (loc.has("title")) aliases.add(loc.get("title").getAsString().trim());
        }
    }
    return aliases;
}

private boolean matchesAnyAlias(String text, String[] aliases) {
    for (String a : aliases) { if (a.length() > 1 && (containsIgnoreCase(a,text) || containsIgnoreCase(text,a))) return true;}
    return false;
}

private boolean isVocaloid(String t) {return Vocaloid.isVocaloid(t);}

private boolean containsOnlyVocaloids(String text) {
    if (text.isEmpty() || text.toUpperCase().contains(" VS ")) return false;
    String temp = text;

    // Remove all vocaloid
    for (Vocaloid v : Vocaloid.values()) {
        // Remove canonical name
        temp = temp.replaceAll("(?i)\\Q" + v.getCanonical() + "\\E[^\u3001,\\s]*", "");
        // Remove all aliases
        for (String alias : v.getAliases()) {
            temp = temp.replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(alias) + "\\b", "");
        }
    }

    // vocaloid w/(β, α)
    temp = temp.replaceAll("[\\p{IsHan}\\p{IsKatakana}\\p{IsHiragana}]+[\u03B1\u03B2]", "");
    temp = temp.replaceAll("[,\u3001\\s]+", "").trim();
    return temp.isEmpty();
}

private String extractVocaloidsFromString(String text) {
    java.util.List<String> found = new java.util.ArrayList<>();
    for (String part : text.split("[,\u3001]")) {
        String p = part.trim();
        if (!p.isEmpty() && found.stream().noneMatch(f -> f.equalsIgnoreCase(p))) {
            found.add(p);
        }
    }
    return String.join(", ", found);
}

private String deduplicateFeat(String feat, String artist) {
    java.util.List<String> unique = new java.util.ArrayList<>();
    if (feat == null) return "";
    for (String p : feat.split("[,\u3001]")) {
        String t = p.trim();
        if (t.isEmpty() || containsIgnoreCase(artist, t)) continue;

        if (isVocaloid(t)){
            String canonical= Vocaloid.normalize(t);
            if (containsIgnoreCase(artist,canonical)) continue;

            boolean foundAliasInArtist = false;
            for (Vocaloid v : Vocaloid.values()) {
                if (v.getCanonical().equalsIgnoreCase(canonical)) {
                    for (String alias : v.getAliases()) {
                        if (containsIgnoreCase(artist, alias)) {
                            foundAliasInArtist = true;
                            break;
                        }
                    }
                }
                if (foundAliasInArtist) break;
            }
            if (foundAliasInArtist) continue;
        }
        String normalized = Vocaloid.normalize(t);

        boolean alreadyInList = false;
        for(String u : unique) {
            if(Vocaloid.normalize(u).equalsIgnoreCase(normalized)) {
                alreadyInList = true;
                break;
            }
        }
        if (!alreadyInList) unique.add(normalized);
    }
    return String.join(", ", unique);
}

private boolean containsIgnoreCase(String src, String what) {
    if (src == null || what == null) return false;
    return src.toLowerCase().contains(what.toLowerCase());
}

    class RequestData {
        String title;
        String artist;
        String videoId;
        String playlistId;
    }
}