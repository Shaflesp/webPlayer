package MetaDataJannitor.Processor;

import java.util.*;
import java.util.regex.*;

public class TitleProcessor extends Processor{

    public TitleProcessor(){}

    public String normalizeTitle(String rawTitle) {
        return rawTitle.replaceAll("[\u2014\u2013\u2215]", "-")
                       .replaceAll("[\u3000\u00A0\u2002-\u200B]", " ")
                       .replaceAll("[\u2018\u2019\u0060\u00B4]", "'")
                       .replaceAll("[\u201C\u201D\u201E\u00AB\u00BB]", "\"")
                       .replaceAll("[\\p{Cf}\\p{Co}\\p{Cn}]", "")
                       .replace("\uFF20", "@")
                       .replace("\uFF0F", "/")
                       .replace("//", "/")
                       .replace("￤", " - ")
                       .trim();
    }

    public String cleanGarbage(String title) {
        Map<GarbagePattern, Integer> counters = new LinkedHashMap<>();
        for (GarbagePattern gp : GarbagePattern.values()) {
            counters.put(gp, 0);
    }
        String prev;
        do {
            prev = title;
            for (GarbagePattern gp : GarbagePattern.values()) {
                Matcher m =gp.pattern.matcher(title);
                if(m.find()){
                    do{gp.increment();}while (m.find());
                    title = m.replaceAll("").trim();
                }
                title = title.replaceAll("\\s{2,}", " ").trim();
            }
        } while (!title.equals(prev));

        title = title.replaceAll("(?i)([^\\s\\u3000a-zA-Z0-9\\[\\(\uFF08\u3010])(feat\\.?|ft\\.?|with|w/|Vo[\\.．]?)", "$1 $2");
        title = title.replaceAll("\\s*[\uFF08(][^)\uFF09]*?/[^)\uFF09]*?(?i)(feat|ft)[^)\uFF09]*?[)\uFF09]", "").trim();
        title = title.replaceAll("\\s*\uFF08[^\uFF09]+\uFF09\\s*$", "").trim();

        return title;
    }

    public String translatetionSeparator(String title) {
        // Pattern 1: Comma separator ", English - translation"
        Pattern commaPattern = Pattern.compile("^(.+?\\s*-\\s*.+?)\\s*,\\s*([^,]+\\s*-\\s*.+)$");
        Matcher commaMatcher = commaPattern.matcher(title);

        if (commaMatcher.find()) {
            String part1 = commaMatcher.group(1).trim();
            String part2 = commaMatcher.group(2).trim();

            boolean p1HasJp = part1.matches(".*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}].*");
            boolean p2HasJp = part2.matches(".*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}].*");
            if (p1HasJp && !p2HasJp) {title = part1;}
        }

        // Pattern 2: Slash separator "/ English - translation"
        Pattern slashPattern = Pattern.compile("^(.+?)\\s*/\\s*([^/]+)\\s*-\\s*.+$");
        Matcher slashMatcher = slashPattern.matcher(title);

        if (slashMatcher.find()) {
            String part1 = slashMatcher.group(1).trim(); // Avant le slash
            String part2 = slashMatcher.group(2).trim(); // Après le slash (avant le tiret)

            boolean p1HasJp = part1.matches(".*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}].*");
            boolean p2HasJp = part2.matches(".*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}].*");
            if (p1HasJp && !p2HasJp) {title = part1;}
        }
        return title;
    }

    public String finalizeFormatting(String title) {
        title = title.replaceAll("[\u201C\u201D\u201E]", "\"")
                     .replaceAll("\uFF08\\s*\uFF09", "") // Remove empty Japanese parens
                     .replaceAll("\\(\\s*\\)", "")       // Remove empty parens
                     .replaceAll("\u300E\\s*\u300F", "") // Remove empty corner brackets
                     .replaceAll("\u3010\\s*\u3011", "") // Remove empty thick brackets
                     .replaceAll("\\[\\s*\\]", "")       // Remove empty square brackets
                     .replaceAll("\\s+", " ")            // Collapse spaces
                     .trim();

        // Fix punctuation around separators
        title = title.replaceAll("([\u3002\u300D\u300F])\\s*-", "$1 - ")
                     .replaceAll("(\\S)([\\(\uFF08])", "$1 $2")
                     .replaceAll("(\\S)([\\u300C\\u300E])", "$1 $2")
                     .replaceAll("\\|+", "-");

        return removeQuotes(title);
    }
}
