package MetaDataJannitor;

import java.net.http.HttpClient;
import java.util.*;
import java.util.regex.*;

import MetaDataJannitor.Processor.ArtistProcessor;
import MetaDataJannitor.Processor.FeatProcessor;
import MetaDataJannitor.Processor.TitleProcessor;
import PlaylistProcessing.*;

public class MetaDataCleaner {

    ArtistProcessor artistProcessor;
    TitleProcessor titleProcessor;
    FeatProcessor featProcessor;
    Song cleanedSong;

    public MetaDataCleaner(String rawTitle, String channelName, String channelId, HttpClient client) throws Exception {
        artistProcessor =new ArtistProcessor(channelName,channelId,client);
        titleProcessor =new TitleProcessor();
        featProcessor =new FeatProcessor();
        cleanedSong=cleanMetadata(rawTitle, channelName);
    }

    public Song getCleanedSong() {return cleanedSong;}

    public Song cleanMetadata(String rawTitle, String channelName) {
        String title = rawTitle;
    
        // === 1. NORMALIZE ===
        title = titleProcessor.normalizeTitle(title);
    
        // === 2. BUILD CHANNEL ALIASES AND EXTRACT EARLY FEATS ===
        String cleanChannel = artistProcessor.getCleanChannel();
        String[] channelAliases = artistProcessor.buildAliasesList(cleanChannel);
    
        title=featProcessor.preliminaryFeatDetector(title);
    
        // === 3. GARBAGE REMOVAL  ===
        title=titleProcessor.cleanGarbage(title);

        // === 4. CLEANUP BLOCKS & PUNCTUATION ===
        title=titleProcessor.translatetionSeparator(title);
        title=titleProcessor.finalizeFormatting(title);
    
        // === 5. EXTRACT FEAT (Early Extraction) ===
        List<String> featList = new ArrayList<>();
    
        Pattern pFeatParen = Pattern.compile("(?i)\\s*[\uFF08(\\[]\\s*(feat\\.?|ft\\.?|with|w/|Vo[\\.．]?)[\\s:]*\\s*([^)\uFF09\\]]+)[)\uFF09\\]]");
        Matcher mFeatParen = pFeatParen.matcher(title);
        while (mFeatParen.find()) featList.add(mFeatParen.group(2).trim());
        title = title.replaceAll("(?i)\\s*[\uFF08(\\[]\\s*(?:feat\\.?|ft\\.?|with|w/|Vo[\\.．]?)[\\s:]*\\s*[^)\uFF09\\]]+[)\uFF09\\]]", "").trim();
    
        Pattern pVo = Pattern.compile("(?i)Vo[\\.．]?[\\s:]+(.+)$");
        Matcher mVo = pVo.matcher(title);
        if (mVo.find()) {
            featList.add(mVo.group(1).trim());
            title = title.substring(0, mVo.start()).trim();
        }
    
    //    Pattern p0e = Pattern.compile("(?i)^(.+?)\\s+(feat\\.?|ft\\.?|with)\\s*(.+?)\\s+(.*)$");
    //    Matcher m0e = p0e.matcher(title);
    //    if (m0e.find()) { //Moe Moe Kyun <3
    //        String pA = m0e.group(1).trim();
    //        String pV = m0e.group(3).trim();
    //        String pT = m0e.group(4).trim();
    //
    //        featList.add(pV);
    //        title = (pA+ pT).trim();
    //    }
    
        Pattern pVer = Pattern.compile("(?i)(?:[\\uFF08(\\[]\\s*(?:([^)\\uFF09\\]]+?)\\s+ver\\.?|ver\\.?\\s+([^)\\uFF09\\]]+?))\\s*[)\\uFF09\\]]|(?:^|\\s)ver\\.?\\s+(.+)$)");
        Matcher mVer = pVer.matcher(title);
        while (mVer.find()) {
            String potentialArtist = null;
    
            if (mVer.group(1) != null) potentialArtist = mVer.group(1);
            else if (mVer.group(2) != null) potentialArtist = mVer.group(2);
            else if (mVer.group(3) != null) potentialArtist = mVer.group(3);
    
            if (potentialArtist != null) {
                potentialArtist = potentialArtist.trim();
                Matcher mSplit = Pattern.compile("^(.+?)\\s*[\\(\\uFF08](.+?)[\\)\\uFF09](.*)$").matcher(potentialArtist);
    
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
        Pattern pFeatSlash = Pattern.compile("(?i)\\s+(feat|ft)[\\.\\s:]*\\s*(.+?)\\s*(?=/)");
        Matcher mFeatSlash = pFeatSlash.matcher(title);
        if (mFeatSlash.find()) {
            featList.add(mFeatSlash.group(2).trim());
            title = mFeatSlash.replaceFirst("").trim();
        }
    
        // 5c. Feat at End
        Pattern pFeatEnd = Pattern.compile("(?i)\\s+(feat|ft|with)[\\.\\s:]*\\s*((?:(?!\\s+-\\s+).)+)\\s*$");
        Matcher mFeatEnd = pFeatEnd.matcher(title);
        if (mFeatEnd.find()){
            if (mFeatEnd.group(1).equalsIgnoreCase("with")&& !Vocaloid.isVocaloid(mFeatEnd.group(2).trim())){} //skip
            else {
                featList.add(mFeatEnd.group(2).trim());
                title = title.substring(0, mFeatEnd.start()).trim();
            }
        }
    
        // 5d. Vocaloid after slash
        if (title.contains("/")) {
            int slashIdx = title.lastIndexOf("/");
            String afterSlash = title.substring(slashIdx + 1).trim();
            if (afterSlash.length() < 20 && Vocaloid.isVocaloid(afterSlash)) {
                featList.add(afterSlash);
                title = title.substring(0, slashIdx).trim();
            }
        }
    
        //5e the metadata skipper
        Pattern pImplicit = Pattern.compile("\\s*\\(([^)]+)\\)[\\s-]*$");
        Matcher mImplicit = pImplicit.matcher(title);
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
    
    //    Pattern pRemix = Pattern.compile("(?i)[\\(\\[]\\s*([^)\\]\\(]+?)\\s+(?:Remix|Mix|Flip|Bootleg|Edit|Rmx)\\s*[\\)\\]]");
    //    Matcher mRemix = pRemix.matcher(title);
    //    StringBuffer sbRemix = new StringBuffer();
    //
    //    while (mRemix.find()) {
    //        String remixer = mRemix.group(1).trim();
    //        if (channel.matchesAnyAlias(remixer, channelAliases)) {
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
            Matcher m = Pattern.compile("^(.+?)\\s*/\\s*(.+?)\\s*\\((.+?)\\s*/\\s*(.+?)\\)$").matcher(title);
            if (m.find()) {
                String beforeSlash = m.group(1).trim();
                String afterSlash = m.group(2).trim();
                String vocal = m.group(4).trim();
    
                // If afterSlash is a vocaloid AND inParen2 references that same vocaloid
                if (Vocaloid.isVocaloid(afterSlash) && (artistProcessor.containsIgnoreCase(vocal, afterSlash) || Vocaloid.isVocaloid(vocal))) {
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
            Matcher m = Pattern.compile("^(.+?)\\s*[\u300E\u300C](.+?)[\u300F\u300D](.*)$").matcher(title);
            if (m.find()) {
                String pA = m.group(1).trim();
                String pT = m.group(2).trim();
                if (artistProcessor.matchesAnyAlias(pA, channelAliases) || pA.length() < 20) {
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
            if (Vocaloid.isVocaloid(after.substring(0,after.indexOf(' ')))
                    && Vocaloid.isVocaloid(after.substring(after.lastIndexOf(' ')))) {
                extractedFeat=extractedFeat+", "+ artistProcessor.extractVocaloidsFromString(after.substring(0,after.indexOf(' '))+","+after.substring(after.lastIndexOf(' ')));
                artist = cleanChannel;
                finalTitle = t;
                found = true;
            }
        }
    
    
        // Pattern 2: Reversed "(Vocaloid) - Producer"
        if (!found) {
            Matcher m = Pattern.compile("^(.+?)\\s*[\uFF08(]([^)\uFF09]+)[)\uFF09]\\s*-\\s*([^-]+)$").matcher(title);
            if (m.find()) {
                String t = m.group(1).trim();
                String v = m.group(2).trim();
                String a = m.group(3).trim();
                if (Vocaloid.isVocaloid(v) && a.length() < 30) {
                    finalTitle = t;
                    artist = artistProcessor.matchesAnyAlias(a,channelAliases) ? a : cleanChannel;
                    if (extractedFeat.isEmpty()) extractedFeat = v;
                    found = true;
                }
            }
        }
    
        // Pattern 3: "Artist feat. X / Trans - Title" (Non-breath fix)
        if (!found) {
            Matcher m = Pattern.compile("^(.+?)\\s*/\\s*(.+?)\\s+-\\s+(.+)$").matcher(title);
            if (m.find()) {
                String p1 = m.group(1).trim(), p2 = m.group(2).trim(), p3 = m.group(3).trim();
                if (Vocaloid.isVocaloid(p2) && p2.length() < 15 && !p2.contains(":")) {} //skip
                else if (artistProcessor.matchesAnyAlias(p1, channelAliases) || p1.toLowerCase().contains("feat") || hasExtractedFeat) {
                    artist = p1;
                    finalTitle = p3.length() >= p2.length() ? p3 : p2;
                    found = true;
                }
                else if (artistProcessor.matchesAnyAlias(p3, channelAliases)) {
                    artist = p3;
                    finalTitle = p2.length() >= p1.length() ? p2 : p1;
                    found = true;
                }
            }
        }
    
        // Pattern 4: "Artist - Title / Trans" (Apple dot com fix)
        if (!found) {
            Matcher m = Pattern.compile("^(.+?)\\s+-\\s+(.+?)\\s*/\\s*(.+)$").matcher(title);
            if (m.find()) {
                String p1 = m.group(1).trim(), p2 = m.group(2).trim(), p3 = m.group(3).trim();
    
                if (artistProcessor.matchesAnyAlias(p1, channelAliases) || hasExtractedFeat) {
                    artist = artistProcessor.matchesAnyAlias(p1,channelAliases) ? p1 :cleanChannel;
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
                artist = artistProcessor.matchesAnyAlias(p1, channelAliases) ? p1 : cleanChannel;
                finalTitle = p3;
                found = true;
            }
            else if (artistProcessor.matchesAnyAlias(p2, channelAliases)) { artist = p2; finalTitle = p1; found = true; }
            else if (artistProcessor.matchesAnyAlias(p1, channelAliases)) { artist = p1; finalTitle = p2; found = true; }
            else if (artistProcessor.matchesAnyAlias(p3, channelAliases)) { artist = p3; finalTitle = p1; found = true; }
            else if (artistProcessor.containsOnlyVocaloids(p3)) { artist = cleanChannel; finalTitle = p1;
                if(extractedFeat.isEmpty()) extractedFeat = artistProcessor.extractVocaloidsFromString(p3); found = true; }
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
                if (Vocaloid.isVocaloid(afterSlash) && afterSlash.length() < 15 && !afterSlash.contains(":")) {
                    String realTitle = p1.substring(0, p1.lastIndexOf(" / ")).trim();
                    finalTitle = realTitle;
                    artist = artistProcessor.matchesAnyAlias(p2,channelAliases) ? p2 : cleanChannel;
                    if (extractedFeat.isEmpty()) {extractedFeat = afterSlash;}
                    found = true;
                    shouldSkip = true;
                }
            }
            if (p2.matches("(?i)^(Normal|Hard|Easy|Instrumental|Off Vocal|Karaoke|Remix|Mix|Original Mix|Extended|Radio Edit|OST|Original Soundtrack)(?:[\\s\\u3000]*[\\(\\[\\uFF08\\u3010].*)?$")) {
                shouldSkip = true;
            }
            if (!shouldSkip) {
                if (artistProcessor.containsOnlyVocaloids(p2)) {
                    artist = cleanChannel;
                    finalTitle = p1;
                    if(extractedFeat.isEmpty()) extractedFeat = artistProcessor.extractVocaloidsFromString(p2);
                }
                else if (artistProcessor.matchesAnyAlias(p2, channelAliases)) { finalTitle = p1; artist = p2; }
                else if (artistProcessor.matchesAnyAlias(p1, channelAliases)) { artist = p1; finalTitle = p2; }
                else if (p1.toLowerCase().contains("feat")) { artist = p1; finalTitle = p2; }
                else if (hasExtractedFeat && p1.length() < 20) { artist = p1; finalTitle = p2; }
                else { artist = p1; finalTitle = p2; }
                found = true;
            }
         }
    
         //Pattern 6b Title -Vocaloid
        if (!found && title.contains("-")) {
            Matcher m = Pattern.compile("^(.+?)\\s*-\\s*([^\\s]+)").matcher(title);
    
            if (m.find()) {
                String beforeDash = m.group(1).trim();
                String afterDash = m.group(2).trim();
    
                if (Vocaloid.isVocaloid(afterDash)) {
                    finalTitle = beforeDash;
                    artist = cleanChannel;
                    if (extractedFeat.isEmpty()) {extractedFeat = afterDash;}
                    found = true;
                }else if (Vocaloid.isVocaloid(beforeDash)) {
                     finalTitle = afterDash;
                     artist = cleanChannel;
                     if (extractedFeat.isEmpty()) {extractedFeat = beforeDash;}
                     found = true;
                }
            }
        }
    
        // Pattern 6c: Slash with Vocaloid "Title / Vocaloid"
        if (!found && title.contains(" / ") && !title.contains(" - ")) {
            Matcher m = Pattern.compile("^(.+?)\\s*/\\s*(.+)$").matcher(title);
            if (m.find()) {
                String beforeSlash = m.group(1).trim();
                String afterSlashRaw = m.group(2).trim();
    
                String potentialVocaloid = afterSlashRaw;
                String suffix = "";
    
                Matcher mSuffix = Pattern.compile("^(.*?)(\\s*[\\(\\[\\uFF08\\u3010][^)\\]\\uFF09\\u3011]+[)\\]\\uFF09\\u3011])$").matcher(afterSlashRaw);
                if (mSuffix.find()) {
                    potentialVocaloid = mSuffix.group(1).trim();
                    suffix = mSuffix.group(2);
                }
    
                // 2. Validate: Is the remaining part a Vocaloid or List?
                boolean isExactVoc = Vocaloid.isVocaloid(potentialVocaloid);
                boolean isStrictList = artistProcessor.containsOnlyVocaloids(potentialVocaloid);
                boolean isLooseList = !isStrictList && potentialVocaloid.matches(".*[,&、・].*") && Vocaloid.isVocaloid(potentialVocaloid);
    
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
    
            Matcher mSuffix = Pattern.compile("^(.+?)\\s+(\\[.+?\\])$").matcher(p2);
            if (mSuffix.find()) {
                p2Clean = mSuffix.group(1).trim();
                p2Suffix = " " + mSuffix.group(2).trim();
            }
            if (artistProcessor.matchesAnyAlias(p1, channelAliases) || p1.toLowerCase().contains("feat")) { artist = p1; finalTitle = p2; }
            else if (artistProcessor.matchesAnyAlias(p2Clean, channelAliases)) { artist = p2Clean; finalTitle = p1+p2Suffix; }
            else { finalTitle = p1+p2Suffix; artist = p2Clean; }
            found = true;
        }
    
        //Pattern 7b: Tight Slash "Title/Artist" (No spaces)
        if (!found && title.contains("/") && !title.contains(" / ")) {
            int slashIdx = title.indexOf("/");
            String p1 = title.substring(0, slashIdx).trim();
            String p2 = title.substring(slashIdx + 1).trim();
    
            boolean matchChannel = artistProcessor.matchesAnyAlias(p2, channelAliases);
            boolean looksLikeArtistList = p2.matches("(?i).*\\s+(&|x|with|feat\\.?|ft\\.?)\\s+.*");
            boolean isVoc = Vocaloid.isVocaloid(p2);
            if (matchChannel || looksLikeArtistList || isVoc) {
                finalTitle = p1;
                artist = p2;
                found = true;
            }
        }
    
        // === 8. FALLBACK ===
        boolean channelIsTopic = channelName.matches("(?i).*\\s-\\sTopic$");
        boolean extractedMatchesChannel = artistProcessor.matchesAnyAlias(artist, channelAliases);
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
            Matcher mSplit = Pattern.compile("^(.+?)(?:\\s+(?:feat\\.?|ft\\.|with)\\s+(.+))?$").matcher(artist);
            if (mSplit.find()) {
                String mainPart = mSplit.group(1);
                String existingFeatPart = mSplit.group(2);
    
                String[] splitArtists = mainPart.split(",");
                String keptMainArtists = "";
                List<String> movedToFeat = new ArrayList<>();
    
                int i=0;
                for (String splitArtist : splitArtists) {
                    String a = splitArtist.trim();
                    i++;
                    if (i!=1 && i==splitArtists.length && !artistProcessor.matchesAnyAlias(a,channelAliases)){
                        keptMainArtists=splitArtists[0];
                        movedToFeat.clear();
                        movedToFeat.addAll(List.of(splitArtists));
                    } else if (artistProcessor.matchesAnyAlias(a, channelAliases)) {
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
    
        if (!artistProcessor.matchesAnyAlias(artist, channelAliases)) {
            String cleannedBaseArtist = baseArtist.replaceAll("[\u300E\u300C][^\u300F\u300D]+[\u300F\u300D]", "").trim();
            cleannedBaseArtist = cleannedBaseArtist.replaceAll("\\s{2,}", " ").trim();
            String cleanedArtist = artist.replaceAll("[\u300E\u300C][^\u300F\u300D]+[\u300F\u300D]", "").trim();
            cleanedArtist = cleanedArtist.replaceAll("\\s{2,}", " ").trim();
    
            if (artistProcessor.matchesAnyAlias(cleannedBaseArtist, channelAliases)) {
                baseArtist= cleannedBaseArtist;
                artist = cleanedArtist;
            }
        }
        //Check if artist is just vocaloids
        if (artistProcessor.containsOnlyVocaloids(baseArtist)) {
            artist= Vocaloid.normalize(baseArtist);
        }
    
        // === 11. APPEND FEAT ===
        if (!extractedFeat.isEmpty()) {
            extractedFeat = extractedFeat.replaceAll("\\s*[&、・•×]\\s*", ", ");
    
            //deduplicate voicebanks
            if (Vocaloid.isVocaloid(artist) && artist.matches("^[\\x00-\\x7F]+$")) {
                String canonicalArtist = Vocaloid.normalize(artist);
                List<String> tempFeats = new ArrayList<>();
                boolean swapped = false;
    
                for (String f : extractedFeat.split(",")) {
                    String ft = f.trim();
                    if (ft.isEmpty()) continue;
                    if (!swapped && Vocaloid.isVocaloid(ft) && !ft.matches("^[\\x00-\\x7F]+$")
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
                Pattern p = Pattern.compile("(?i)(?:feat\\.?|ft\\.)\\s+(.+)$");
                Matcher m = p.matcher(artist);
                if (m.find()) {
                    existingFeats = m.group(1).trim();
                }
                artist = baseArtist;
            }
    
            // Merge existing feats with new feats
            String allFeats = existingFeats.isEmpty() ? extractedFeat : existingFeats + ", " + extractedFeat;
    
            allFeats = featProcessor.deduplicateFeat(allFeats, artist);
            if (!allFeats.isEmpty()) {
                artist = artist + " feat. " + allFeats;
            }
        }
    
        return finalize(finalTitle, artist);
    }

    private Song finalize(String title, String artist) {
        title = title.trim().replaceAll("^[/\\-:\uFF1A]+|[/\\-:\uFF1A]+$", "").trim();
        artist = artist.trim().replaceAll("^[/\\-:\uFF1A]+|[/\\-:\uFF1A]+$", "").trim();
        title = titleProcessor.removeQuotes(title);
        artist = artistProcessor.removeQuotes(artist);

        // Remove dangling brackets
        if (title.matches(".*[\u300D\u300F'\"]$") && !title.matches("^[\u300C\u300E'\"].*")) {
             title = title.replaceAll("[\u300D\u300F'\"]+$", "").trim();
        }
        if (title.matches("^[\u300C\u300E'\"].*") && !title.matches(".*[\u300D\u300F'\"]$")) {
             title = title.replaceAll("^[\u300C\u300E'\"]+", "").trim();
        }

        title=titleProcessor.removeOrphanedQuotes(title);
        //Azari
    //    if (title.isBlank()) title = "Unknown Title";
    //    if (artist.isBlank()) artist = "Unknown Artist";
        return new Song(title, artist);
    }
}
