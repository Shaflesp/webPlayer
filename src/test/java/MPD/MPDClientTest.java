package MPD;

import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests MPDClient's protocol parsing using a tiny fake MPD server rather than
 * a real MPD instance — these tests run anywhere, instantly, with no audio
 * daemon required. The fake server sends the standard greeting, then for each
 * scripted response block, reads one command line from the client and replies
 * with that exact block (so the test fully controls what MPDClient parses).
 */
class MPDClientTest {

    private ServerSocket serverSocket;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws IOException {
        serverSocket = new ServerSocket(0); // OS picks a free port
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        try { serverSocket.close(); } catch (IOException ignored) {}
    }

    private MPDClient fakeServer(String... responseBlocks) throws IOException {
        // No CountDownLatch needed: the server thread blocks in accept() until
        // a connection arrives, and the test thread's `new MPDClient(...)` call
        // below both PROVIDES that connection AND blocks reading the greeting
        // line inside its constructor — that blocking read is itself the
        // synchronization point, so an extra explicit signal isn't needed
        // (and previously, awaiting one BEFORE the connection existed was a
        // straight deadlock: the server waits in accept() for a connection
        // that the test thread never initiates because it's stuck waiting on
        // the very signal only a connection can trigger).
        executor.submit(() -> {
            try (Socket client = serverSocket.accept()) {
                OutputStream out = client.getOutputStream();
                out.write("OK MPD 0.23.5\n".getBytes());
                out.flush();

                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                for (String block : responseBlocks) {
                    in.readLine(); // consume the command line sent by MPDClient
                    out.write(block.getBytes());
                    out.flush();
                }
            } catch (IOException ignored) {}
        });
        return new MPDClient("localhost", serverSocket.getLocalPort());
    }

    // ── escape() ──────────────────────────────────────────────────────────────

    @Test
    void escape_escapesBackslashes() {
        assertEquals("a\\\\b", MPDClient.escape("a\\b"));
    }

    @Test
    void escape_escapesQuotes() {
        assertEquals("a\\\"b", MPDClient.escape("a\"b"));
    }

    @Test
    void escape_leavesPlainTextUnchanged() {
        assertEquals("hello world", MPDClient.escape("hello world"));
    }

    // ── command() ─────────────────────────────────────────────────────────────

    @Test
    void command_returnsLinesBeforeOK() throws Exception {
        try (MPDClient client = fakeServer("volume: 80\nOK\n")) {
            List<String> lines = client.command("status");
            assertEquals(List.of("volume: 80"), lines);
        }
    }

    @Test
    void command_throwsIOExceptionOnAck() throws Exception {
        try (MPDClient client = fakeServer("ACK [5@0] {play} No such song\n")) {
            IOException ex = assertThrows(IOException.class, () -> client.command("play 999"));
            assertTrue(ex.getMessage().contains("No such song"),
                    "expected the raw ACK text in the exception message, got: " + ex.getMessage());
        }
    }

    // ── commandAsMap() ────────────────────────────────────────────────────────

    @Test
    void commandAsMap_parsesKeyValuePairs() throws Exception {
        try (MPDClient client = fakeServer("volume: 80\nstate: play\nsongid: 3\nOK\n")) {
            Map<String, String> status = client.commandAsMap("status");
            assertEquals("80",   status.get("volume"));
            assertEquals("play", status.get("state"));
            assertEquals("3",    status.get("songid"));
        }
    }

    @Test
    void commandAsMap_onlySplitsOnFirstColon() throws Exception {
        // Filenames/titles can legitimately contain colons — only the
        // FIRST colon in a line should separate key from value.
        try (MPDClient client = fakeServer("file: Music/Artist: Title.mp3\nOK\n")) {
            Map<String, String> song = client.commandAsMap("currentsong");
            assertEquals("Music/Artist: Title.mp3", song.get("file"));
        }
    }

    // ── commandAsBlocks() ─────────────────────────────────────────────────────

    @Test
    void commandAsBlocks_splitsOnRepeatingKey() throws Exception {
        try (MPDClient client = fakeServer(
                "file: a.mp3\nTitle: A\nfile: b.mp3\nTitle: B\nOK\n"
        )) {
            List<Map<String, String>> songs = client.commandAsBlocks("playlistinfo", "file");
            assertEquals(2, songs.size());
            assertEquals("a.mp3", songs.get(0).get("file"));
            assertEquals("A",     songs.get(0).get("Title"));
            assertEquals("b.mp3", songs.get(1).get("file"));
            assertEquals("B",     songs.get(1).get("Title"));
        }
    }

    @Test
    void commandAsBlocks_emptyResultWhenNothingMatches() throws Exception {
        try (MPDClient client = fakeServer("OK\n")) {
            List<Map<String, String>> songs = client.commandAsBlocks("playlistinfo", "file");
            assertTrue(songs.isEmpty());
        }
    }

    // ── commandAsLsBlocks() ───────────────────────────────────────────────────

    @Test
    void commandAsLsBlocks_tagsTypePerBlock() throws Exception {
        try (MPDClient client = fakeServer(
                "directory: Music/Rock\nfile: Music/song.mp3\nTitle: Song\nOK\n"
        )) {
            List<Map<String, String>> items = client.commandAsLsBlocks("lsinfo");
            assertEquals(2, items.size());
            assertEquals("directory",    items.get(0).get("_type"));
            assertEquals("Music/Rock",   items.get(0).get("directory"));
            assertEquals("file",         items.get(1).get("_type"));
            assertEquals("Music/song.mp3", items.get(1).get("file"));
        }
    }
}