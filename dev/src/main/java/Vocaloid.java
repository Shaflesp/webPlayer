public enum Vocaloid {
    MIKU("初音ミク", "Hatsune Miku", "Miku Hatsune", "Miku"),
    TETO("重音テト", "Kasane Teto", "Teto Kasane", "Teto","Tet0"),
    RIN("鏡音リン", "Kagamine Rin", "Rin Kagamine", "Rin"),
    LEN("鏡音レン", "Kagamine Len", "Len Kagamine", "Len"),
    LUKA("巡音ルカ", "Megurine Luka", "Luka Megurine", "Luka"),
    ZUNDAMON("ずんだもん", "Zundamon"),
    UNA("音街ウナ", "Otomachi Una", "Una Otomachi","Una"),
    KAFU("可不", "Kafu"),
    SEKAI("星界", "Sekai"),
    YUKI("歌愛ユキ", "Kaai Yuki", "Yuki Kaai", "Yuki"),
    GUMI("グミ", "Gumi","Gumi Eng"),
    IA("イア", "Ia"),
    FLOWER("フラワ", "ブイフラワ","flower","v flower","Ci flower"),
    KAITO("カイト", "Kaito"),
    MEIKO("メイコ", "Meiko"),
    REI("足立レイ", "Adachi Rei", "Rei Adachi"),
    KAZEHIKIB("カゼヒキβ", "Kazehiki Beta", "Beta Kazehiki", "Kazehiki β"),
    GEKIYAKUB("ゲキヤクβ", "Gekiyaku Beta", "Beta Gekiyaku", "Gekiyaku β"),
    KOTONOHA("琴葉 茜・葵","Kotonoha Akane & Aoi", "Akane & Aoi Kotonoha", "Kotonoha Twins"),
    NERU("亞北ネル", "Akita Neru", "Neru Akita", "Neru"),
    LEUR("ルウル","LeuR");

    private final String canonical;  // Preferred Japanese name
    private final String[] aliases;

    Vocaloid(String canonical, String... aliases) {
        this.canonical = canonical;
        this.aliases = aliases;
    }

    public String getCanonical() { return canonical; }
    public String[] getAliases() { return aliases; }

    public boolean matches(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase().trim();
        if (canonical.equalsIgnoreCase(lower)) return true;
        for (String alias : aliases) {
            if (alias.equalsIgnoreCase(lower)) return true;
        }
        return false;
    }

    public static Vocaloid find(String text) {
        for (Vocaloid v : values()) {
            if (v.matches(text)) return v;
        }
        return null;
    }

    public static boolean isVocaloid(String text) {
        return find(text) != null;
    }

    public static String normalize(String text) {
        Vocaloid v = find(text);
        return v != null ? v.getCanonical() : text;
    }
}