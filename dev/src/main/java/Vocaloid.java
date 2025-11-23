public enum Vocaloid {
    MIKU("初音ミク", "Hatsune Miku", "Miku Hatsune", "Miku"),
    TETO("重音テト", "Kasane Teto", "Teto Kasane", "Teto"),
    RIN("鏡音リン", "Kagamine Rin", "Rin Kagamine", "Rin"),
    LEN("鏡音レン", "Kagamine Len", "Len Kagamine", "Len"),
    LUKA("巡音ルカ", "Megurine Luka", "Luka Megurine", "Luka"),
    UNA("音街ウナ", "Otomachi Una", "Una Otomachi"),
    KAFU("可不", "Kafu"),
    SEKAI("星界", "Sekai"),
    GUMI("GUMI", "Gumi"),
    IA("IA", "Ia"),
    FLOWER("flower", "Flower"),
    KAITO("KAITO", "Kaito"),
    MEIKO("MEIKO", "Meiko");

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
        if (canonical.equalsIgnoreCase(text)) return true;
        for (String alias : aliases) {
            if (alias.equalsIgnoreCase(text)) return true;
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