package MetaDataJannitor.Processor;

public abstract class Processor {

    public String removeOrphanedQuotes(String text) {
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

    public String removeQuotes(String text) {
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

    public boolean containsIgnoreCase(String src, String what) {
        if (src == null || what == null) return false;
        return src.toLowerCase().contains(what.toLowerCase());
    }
}
