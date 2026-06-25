package MPD.service;

import MPD.DependencyManager;
import MPD.config.AppSettings;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SyncServiceFileTest {

    @TempDir
    Path tempDir;

    /**
     * extractIdFolders/readCsv/writeCsv don't touch settings or deps at all —
     * a plain `new` with no Spring context is enough to get an instance to
     * call them on. @PostConstruct on DependencyManager only fires under
     * Spring's lifecycle, so this never triggers real system probing.
     */
    private SyncService newService() {
        return new SyncService(new AppSettings(), new DependencyManager());
    }

    // ── extractIdFolders ──────────────────────────────────────────────────────

    private SyncService.SyncJob dummyJob() {
        return new SyncService.SyncJob("job", "https://example.com");
    }

    @Test
    void extractIdFolders_movesFileOutOfIdFolderAndRemovesIt() throws IOException {
        Path idFolder = tempDir.resolve("dQw4w9WgXcQ"); // 11 chars
        Files.createDirectories(idFolder);
        Files.writeString(idFolder.resolve("Song Title.opus"), "fake audio");

        newService().extractIdFolders(tempDir, dummyJob());

        assertTrue(Files.exists(tempDir.resolve("Song Title.opus")), "file should be moved to parent");
        assertFalse(Files.exists(idFolder), "now-empty ID folder should be removed");
    }

    @Test
    void extractIdFolders_handlesMultipleIdFoldersIndependently() throws IOException {
        Path id1 = tempDir.resolve("aaaaaaaaaaa"); // 11 chars
        Path id2 = tempDir.resolve("bbbbbbbbbbb"); // 11 chars
        Files.createDirectories(id1);
        Files.createDirectories(id2);
        Files.writeString(id1.resolve("First.opus"),  "a");
        Files.writeString(id2.resolve("Second.opus"), "b");

        newService().extractIdFolders(tempDir, dummyJob());

        assertTrue(Files.exists(tempDir.resolve("First.opus")));
        assertTrue(Files.exists(tempDir.resolve("Second.opus")));
        assertFalse(Files.exists(id1));
        assertFalse(Files.exists(id2));
    }

    @Test
    void extractIdFolders_appendsInvisibleCharacterOnNameCollision() throws IOException {
        // A file with this exact name already exists in the parent —
        // simulating two different videos that happened to produce the
        // same title.
        Files.writeString(tempDir.resolve("Same Title.opus"), "existing");

        Path idFolder = tempDir.resolve("ccccccccccc"); // 11 chars
        Files.createDirectories(idFolder);
        Files.writeString(idFolder.resolve("Same Title.opus"), "incoming");

        newService().extractIdFolders(tempDir, dummyJob());

        // Original file untouched
        assertEquals("existing", Files.readString(tempDir.resolve("Same Title.opus")));

        // The colliding file should have landed under a DIFFERENT name —
        // exactly one with the invisible character (U+3164) appended.
        Path deduped = tempDir.resolve("Same Title\u3164.opus");
        assertTrue(Files.exists(deduped), "expected a deduplicated file with the invisible-char suffix");
        assertEquals("incoming", Files.readString(deduped));
    }

    @Test
    void extractIdFolders_appendsMultipleInvisibleCharactersIfNeededForUniqueness() throws IOException {
        Files.writeString(tempDir.resolve("Dup.opus"), "0");
        Files.writeString(tempDir.resolve("Dup\u3164.opus"), "1"); // already one collision resolved previously

        Path idFolder = tempDir.resolve("ddddddddddd");
        Files.createDirectories(idFolder);
        Files.writeString(idFolder.resolve("Dup.opus"), "2");

        newService().extractIdFolders(tempDir, dummyJob());

        assertTrue(Files.exists(tempDir.resolve("Dup\u3164\u3164.opus")),
            "should keep appending the invisible char until the name is actually unique");
    }

    @Test
    void extractIdFolders_ignoresFoldersNotExactlyElevenCharacters() throws IOException {
        Path notAnId = tempDir.resolve("short"); // 5 chars, not 11
        Files.createDirectories(notAnId);
        Files.writeString(notAnId.resolve("file.opus"), "data");

        newService().extractIdFolders(tempDir, dummyJob());

        assertTrue(Files.exists(notAnId), "non-ID-shaped folder should be left completely alone");
        assertTrue(Files.exists(notAnId.resolve("file.opus")));
    }

    @Test
    void extractIdFolders_ignoresPlainFilesEvenWithElevenCharacterNames() throws IOException {
        // A FILE (not a directory) whose name happens to be 11 characters —
        // must not be mistaken for an ID folder.
        Files.writeString(tempDir.resolve("elevenchar1"), "not a folder");

        newService().extractIdFolders(tempDir, dummyJob());

        assertTrue(Files.exists(tempDir.resolve("elevenchar1")), "should be untouched — it's a file, not a directory");
    }

    @Test
    void extractIdFolders_removesEmptyIdFolderWithNoFilesInside() throws IOException {
        Path idFolder = tempDir.resolve("eeeeeeeeeee");
        Files.createDirectories(idFolder);

        newService().extractIdFolders(tempDir, dummyJob());

        assertFalse(Files.exists(idFolder), "empty ID folder should still be cleaned up");
    }

    @Test
    void extractIdFolders_preservesExtensionThroughRename() throws IOException {
        Files.writeString(tempDir.resolve("Track.mp3"), "existing"); // force a collision
        Path idFolder = tempDir.resolve("fffffffffff");
        Files.createDirectories(idFolder);
        Files.writeString(idFolder.resolve("Track.mp3"), "incoming");

        newService().extractIdFolders(tempDir, dummyJob());

        Path deduped = tempDir.resolve("Track\u3164.mp3");
        assertTrue(Files.exists(deduped), "extension should still be .mp3 after the invisible-char insert, not lost or misplaced");
    }

    // ── CSV read/write ────────────────────────────────────────────────────────

    @Test
    void readCsv_returnsEmptyMapWhenFileDoesNotExist() {
        Map<String, String[]> result = newService().readCsv(tempDir);
        assertTrue(result.isEmpty());
    }

    @Test
    void writeCsv_thenReadCsv_roundTripsCorrectly() {
        SyncService service = newService();
        Map<String, String[]> data = new LinkedHashMap<>();
        data.put("My Playlist", new String[]{ "https://youtube.com/playlist?list=abc123", "2026-01-01 12:00:00" });

        service.writeCsv(tempDir, data);
        Map<String, String[]> read = service.readCsv(tempDir);

        assertEquals(1, read.size());
        assertArrayEquals(data.get("My Playlist"), read.get("My Playlist"));
    }

    @Test
    void readCsv_skipsCommentAndBlankLines() throws IOException {
        Files.writeString(tempDir.resolve("webplayer-playlists.csv"),
            "# this is a header comment\n\nReal Entry,https://example.com,2026-01-01 00:00:00\n");

        Map<String, String[]> result = newService().readCsv(tempDir);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("Real Entry"));
    }

    @Test
    void readCsv_preservesCommasInsideTheUrlField() throws IOException {
        // YouTube URLs often have query strings; the format only splits on
        // the FIRST TWO commas so the URL field can safely contain its own.
        String url = "https://youtube.com/watch?v=abc,123,456";
        Files.writeString(tempDir.resolve("webplayer-playlists.csv"),
            "Name," + url + ",2026-01-01 00:00:00\n");

        Map<String, String[]> result = newService().readCsv(tempDir);

        assertEquals(url, result.get("Name")[0]);
    }

    @Test
    void writeCsv_overwritesPreviousContentEntirely() {
        SyncService service = newService();
        service.writeCsv(tempDir, new LinkedHashMap<>(Map.of(
            "Old", new String[]{ "url1", "ts1" }
        )));
        service.writeCsv(tempDir, new LinkedHashMap<>(Map.of(
            "New", new String[]{ "url2", "ts2" }
        )));

        Map<String, String[]> result = service.readCsv(tempDir);
        assertFalse(result.containsKey("Old"), "writeCsv should replace the file, not append to it");
        assertTrue(result.containsKey("New"));
    }
}
