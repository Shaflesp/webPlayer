package MPD.service;

import MPD.MPDClient;
import MPD.config.AppSettings;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

/**
 * Thin Spring service wrapping MpdClient construction so controllers
 * don't need to know the host/port.
 */
@Service
public class MpdService {

    private final AppSettings settings;

    public MpdService(AppSettings settings) {
        this.settings = settings;
    }

    /** Opens a new MpdClient. Caller must close it (use try-with-resources). */
    public MPDClient connect() throws IOException {
        return new MPDClient(settings.get("mpd.host"), settings.getInt("mpd.port"));
    }

    /** Convenience: open, run one command, close. */
    public void command(String cmd) throws IOException {
        try (MPDClient mpd = connect()) { mpd.command(cmd); }
    }

    /** Quick status check: returns true if MPD is reachable. */
    public Map<String, String> probe(String host, int port) throws IOException {
        try (MPDClient mpd = new MPDClient(host, port)) {
            return mpd.commandAsMap("status");
        }
    }
}
