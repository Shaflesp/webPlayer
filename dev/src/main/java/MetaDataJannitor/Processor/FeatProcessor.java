package MetaDataJannitor.Processor;

import MetaDataJannitor.Vocaloid;

import java.util.*;
import java.util.regex.*;

public class FeatProcessor extends Processor{
    public FeatProcessor(){}

    public String preliminaryFeatDetector(String title){
        Matcher mBracketStart = Pattern.compile("^【(.+?)】\\s*(.+)$").matcher(title);
        if (mBracketStart.find()) {
            String content = mBracketStart.group(1).trim();
            String restOfTitle = mBracketStart.group(2).trim();
            StringBuilder extractedFeat = new StringBuilder();

            String[] cleanNames = content.replaceAll("(?i)\\s*(?:[×x&]|soft)\\s*", ", ").split(", ");
            for (String vocal : cleanNames) {
                vocal = vocal.trim();
                if (Vocaloid.isVocaloid(vocal)) {
                    if (extractedFeat.isEmpty()) {
                        extractedFeat = new StringBuilder(vocal);
                    } else {
                        extractedFeat.append(", ").append(vocal);
                    }
                }
            }
            title=restOfTitle+((extractedFeat.isEmpty()) ? "" : " (feat. " + extractedFeat + ")");
        }
        return title;
    }

    public String deduplicateFeat(String feat, String artist) {
        List<String> unique = new ArrayList<>();
        if (feat == null) return "";
        for (String p : feat.split("[,\u3001]")) {
            String t = p.trim();
            if (t.isEmpty() || containsIgnoreCase(artist, t)) continue;

            if (Vocaloid.isVocaloid(t)){
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
}
