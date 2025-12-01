package MetaDataJannitor.Processor;

import java.util.regex.*;

public enum GarbagePattern {
    // === 1. SYMBOLS ===
    SYMBOLS("[\u2190-\u21FF\u2727◤◢\u25b6\u25c0]"),

    // === 2. BLOCKS (Brackets) ===
    BLOCK_CHINESE("\u3010[^\u3011]*\u3011"), 
    
    // Japanese brackets containing MV/Nightcore/Official
    BLOCK_JAPANESE_SPECIFIC("(?i)[\u300C\u300E][^\u300D\u300F]*?(nightcore|sped\\s*up|mv|official|\uFF2D\uFF36)[^\u300D\u300F]*[\u300D\u300F]"),

    // === 3. THE MASTER PARENTHESES/BRACKET CLEANER ===
    METADATA_BLOCKS("(?i)(?:[\\[\\(\uFF08]\\s*|\\s+-\\s+)(?:official|mv|music\\s*video|video|audio|lyrics?|lyric\\s*video|hq|hd|4k|original|clip|officiel|nv|nightcore|sped\\s*up|speed\\s*up|visuali[sz]er|theme\\s*song|full\\s*album|cover|self\\s*-?\\s*cover|remastered)(?:\\s*video|\\s*audio)?(?:\\s*(?:ver\\.|version|mix))?[^\\]\\)\uFF09]*[\\]\\)\uFF09]"),

    // === 4. AGGRESSIVE FLUSH  ===
    AGGRESSIVE_KEYWORDS("(?i)(?:nightcore|sped\\s*up|speed\\s*up)"),

    // === 5. SPECIFIC TAGS ===
    TAGS_GENRE_LABEL("(?i)[\\[\\(](?:Monstercat.*|NCS(?: Release)?|CeVIO AI|Drumstep|Dubstep|House|Trap|DnB|Electro|Glitch Hop|Future Bass|Switching Vocals)[\\]\\)]"),

    // === 6. SUFFIXES ===
    SUFFIX_MASTER("(?i)\\s*(?:[・•-]?\\s*)?(?:OFFICIAL\\s*(?:VIDEO|AUDIO|MV|HD|HQ)?|MUSIC\\s*VIDEO|MV|HD|HQ|remastered|\\+\\s*(?:mp3|dl)|lyrics?|with\\s*translation|SV(?:.*)?)(?=(?:[\uFF08(\\[]|$))"),
    
    SUFFIX_COVERS("(?i)\\s*(?:full\\s*)?(?:self\\s*-?\\s*)?cover(?:\\s*\\(cover\\))?(?=\\s*-|$)"),

    // === 7. ARTIFACTS & JAPANESE ===
    JAPANESE_VERBS("(?:\u6B4C\u3044\u307E\u3057\u305F|\u6B4C\u3063\u3066\u307F\u305F)"),
    ARTIFACTS("(?i)(?:\\s+MV(?=[\\s\u300E\u300C]|$)|\\S*?(?:MV|\uFF2D\uFF36)(?=[\\u300C\\u300D\u300E\u300F\\(\\[\\{\\s]|$))"),
    
    // === 8. CLEANUP ===
    EMPTY_CLEANUP("\\s*(?:\\[\\s*\\]|\\(\\s*\\)|\u3010\\s*\u3011|\uFF08\\s*\uFF09)");

    public final Pattern pattern;
    private int matchCount = 0;

    GarbagePattern(String regex) {
        this.pattern = Pattern.compile(regex);
    }

    //debug
    public void increment() {
        matchCount++;
    }

    public int getMatchCount() {
        return matchCount;
    }

    static public void printUsage() {
        System.out.println("=== Pattern totals since program start ===");
        for (GarbagePattern gp : GarbagePattern.values()) {
            System.out.println(gp + ": " + gp.getMatchCount());
        }
    }
}
