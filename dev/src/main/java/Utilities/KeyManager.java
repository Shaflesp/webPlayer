package Utilities;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class KeyManager {

    private static final Map<String, String> keys = new HashMap<>();
    private static boolean initialized = false;

    public static void loadCSV(String resourceName) {
        if (initialized) return;
        initialized = true;

        try {
            InputStream is = KeyManager.class.getClassLoader().getResourceAsStream(resourceName);
            if (is == null) {
                throw new RuntimeException("Fichier introuvable dans resources : " + resourceName);
            }

            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {

                String line = br.readLine(); // ignorer header

                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(",");

                    if (parts.length >= 2) {
                        String keyName = parts[0].trim();
                        String apiKey = parts[1].trim();
                        keys.put(keyName, apiKey);
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String get(String keyName) {
        loadCSV("keys.csv");
        return keys.get(keyName);
    }
}
