package MetaDataJannitor.Processor;

import MetaDataJannitor.Vocaloid;
import PlaylistProcessing.AddServlet;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.regex.*;

public class ArtistProcessor extends Processor{

    private final String YT_CHANNEL_URL = "https://www.googleapis.com/youtube/v3/channels?part=snippet,localizations&key=" + AddServlet.API_KEY + "&id=";

    String cleanChannel;
    List<String> channelAliases;
    String channelId;
    HttpClient client;

    public ArtistProcessor(String name,String channelId, HttpClient client) throws Exception {
        this(channelId,client);
        this.cleanChannel=sanitizeChannelName(name);
    }
    public ArtistProcessor(String name){this.cleanChannel=sanitizeChannelName(name);}
    public ArtistProcessor(String channelId, HttpClient client) throws Exception {
        this.channelId=channelId;
        this.client=client;
        channelAliases=fetchChannelAliases(channelId,client);
    }

    public List<String> getChannelAliases() {return channelAliases;}
    public void setChannelAliases(List<String> channelAliases) {this.channelAliases = channelAliases;}

    public String getCleanChannel() {return cleanChannel;}
    public void setCleanChannel(String cleanChannel) {this.cleanChannel = cleanChannel;}

    public String[] buildAliasesList(String cleanChannel){
        List<String> aliasesList = new ArrayList<>();
        aliasesList.add(cleanChannel);
        for (String a : cleanChannel.split("[/\uFF0F]")) {
            String t = a.trim();
            if (!t.isEmpty()) aliasesList.add(t);
        }

        if (channelId != null && !channelId.isEmpty() && client != null) {
            try {
                List<String> apiAliases = fetchChannelAliases(channelId,client);
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
        return aliasesList.toArray(new String[0]);
    }

    public  List<String> fetchChannelAliases(String channelId, HttpClient client) throws IOException, InterruptedException {
        List<String> aliases = new ArrayList<>();
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

    public String sanitizeChannelName(String name) {
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

    public boolean matchesAnyAlias(String text, String[] aliases) {
        for (String a : aliases) { if (a.length() > 1 && (containsIgnoreCase(a,text) || containsIgnoreCase(text,a))) return true;}
        return false;
    }

    public boolean containsOnlyVocaloids(String text) {
        if (text.isEmpty() || text.toUpperCase().contains(" VS ")) return false;
        String temp = text;

        // Remove all vocaloid
        for (Vocaloid v : Vocaloid.values()) {
            // Remove canonical name
            temp = temp.replaceAll("(?i)\\Q" + v.getCanonical() + "\\E[^\u3001,\\s]*", "");
            // Remove all aliases
            for (String alias : v.getAliases()) {
                temp = temp.replaceAll("(?i)\\b" + Pattern.quote(alias) + "\\b", "");
            }
        }
        return temp.isEmpty();
    }

    public String extractVocaloidsFromString(String text) {
        List<String> found = new ArrayList<>();
        for (String part : text.split("[,\u3001]")) {
            String p = part.trim();
            if (!p.isEmpty() && found.stream().noneMatch(f -> f.equalsIgnoreCase(p))) {
                found.add(p);
            }
        }
        return String.join(", ", found);
    }
}
