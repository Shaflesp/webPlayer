package MPD.controller;

import MPD.MPDClient;
import MPD.config.WebConfig;
import MPD.service.MpdService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests MpdController's own routing and coercion logic — NOT MPDClient's
 * TCP/parsing behaviour, which MPDClientTest already covers separately.
 *
 * Requests are sent as real JSON strings through MockMvc (not hand-built Java
 * objects) specifically so the REAL registered message converter deserialises
 * them — this is what makes the coercion tests below faithfully reproduce the
 * original "ACK Integer expected: 13.0" bug rather than just testing toInt()
 * in isolation against a value we already know is a Double.
 */
@WebMvcTest(MpdController.class)
@Import(WebConfig.class)
class MpdControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean  MpdService mpdService;

    private MPDClient mockClient() throws Exception {
        MPDClient client = mock(MPDClient.class);
        when(mpdService.connect()).thenReturn(client);
        return client;
    }

    // ── JSON number coercion (the historical Integer-vs-Double bug class) ────

    @Test
    void playid_coercesJsonNumberToPlainInteger_notFloatingPoint() throws Exception {
        MPDClient client = mockClient();

        mockMvc.perform(post("/MPDServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"playid\",\"id\":13}"))
            .andExpect(status().isOk());

        // Gson deserialises "13" into a Map<String,Object> as Double(13.0) —
        // if toInt() didn't coerce it back, this would be "playid 13.0",
        // which MPD rejects with "ACK Integer expected".
        verify(client).command("playid 13");
    }

    @Test
    void delete_coercesPositionToPlainInteger() throws Exception {
        MPDClient client = mockClient();

        mockMvc.perform(post("/MPDServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"delete\",\"pos\":7}"))
            .andExpect(status().isOk());

        verify(client).command("delete 7");
    }

    @Test
    void setvol_coercesVolumeToPlainInteger() throws Exception {
        MPDClient client = mockClient();

        mockMvc.perform(post("/MPDServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"setvol\",\"volume\":80}"))
            .andExpect(status().isOk());

        verify(client).command("setvol 80");
    }

    @Test
    void move_coercesBothPositionsToPlainIntegers() throws Exception {
        MPDClient client = mockClient();

        mockMvc.perform(post("/MPDServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"move\",\"from\":2,\"to\":5}"))
            .andExpect(status().isOk());

        verify(client).command("move 2 5");
    }

    @Test
    void seek_wholeNumberSecondsHasNoTrailingDecimalPoint() throws Exception {
        MPDClient client = mockClient();

        mockMvc.perform(post("/MPDServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"seek\",\"time\":30}"))
            .andExpect(status().isOk());

        // toDouble() special-cases whole numbers so MPD gets "30", not "30.0"
        verify(client).command("seekcur 30");
    }

    @Test
    void seek_fractionalSecondsArePreserved() throws Exception {
        MPDClient client = mockClient();

        mockMvc.perform(post("/MPDServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"seek\",\"time\":30.5}"))
            .andExpect(status().isOk());

        // A genuinely fractional seek position must NOT be truncated —
        // that would make precise seeking impossible.
        verify(client).command("seekcur 30.5");
    }

    // ── Status-then-toggle sequencing ─────────────────────────────────────────

    @Test
    void toggleRandom_flipsFromOffToOn() throws Exception {
        MPDClient client = mockClient();
        when(client.commandAsMap("status")).thenReturn(Map.of("random", "0"));

        mockMvc.perform(post("/MPDServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"toggle_random\"}"))
            .andExpect(status().isOk());

        verify(client).command("random 1");
    }

    @Test
    void toggleRandom_flipsFromOnToOff() throws Exception {
        MPDClient client = mockClient();
        when(client.commandAsMap("status")).thenReturn(Map.of("random", "1"));

        mockMvc.perform(post("/MPDServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"toggle_random\"}"))
            .andExpect(status().isOk());

        verify(client).command("random 0");
    }

    // ── addplay: add, then play the last (newly added) position ──────────────

    @Test
    void addplay_addsThenPlaysTheLastPosition() throws Exception {
        MPDClient client = mockClient();
        when(client.commandAsMap("status")).thenReturn(Map.of("playlistlength", "5"));

        mockMvc.perform(post("/MPDServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"addplay\",\"uri\":\"Music/song.mp3\"}"))
            .andExpect(status().isOk());

        var inOrder = inOrder(client);
        inOrder.verify(client).command("add \"Music/song.mp3\"");
        inOrder.verify(client).command("play 4"); // length 5 → newly-added at index 4
    }

    // ── playlistsave: rm-then-save, save still runs even if rm fails ──────────

    @Test
    void playlistSave_savesEvenWhenRmFailsBecausePlaylistDidNotExistYet() throws Exception {
        MPDClient client = mockClient();
        // Simulates MPD rejecting "rm" for a playlist that doesn't exist yet —
        // the rm-then-save pattern must tolerate this and still save.
        doThrow(new java.io.IOException("ACK No such playlist"))
            .when(client).command("rm \"New Playlist\"");

        mockMvc.perform(post("/MPDServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"playlistsave\",\"name\":\"New Playlist\"}"))
            .andExpect(status().isOk());

        verify(client).command("save \"New Playlist\"");
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void unknownAction_returnsServerError() throws Exception {
        mockClient();

        mockMvc.perform(post("/MPDServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"not_a_real_action\"}"))
            .andExpect(status().isBadRequest());
    }

    // ── GET endpoints ─────────────────────────────────────────────────────────

    @Test
    void nowPlaying_returnsStatusAndSongTogether() throws Exception {
        MPDClient client = mockClient();
        when(client.commandAsMap("status")).thenReturn(Map.of(
            "state", "play", "elapsed", "12.3", "duration", "180.0", "volume", "80"
        ));
        when(client.commandAsMap("currentsong")).thenReturn(Map.of("file", "song.mp3", "Title", "Test"));

        mockMvc.perform(get("/MPDServlet").param("action", "nowplaying"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status.state").value("play"))
            .andExpect(jsonPath("$.status.volume").value(80))
            .andExpect(jsonPath("$.song.file").value("song.mp3"));
    }

    @Test
    void browse_withEmptyUri_callsPlainLsinfoWithoutQuotedEmptyString() throws Exception {
        MPDClient client = mockClient();
        when(client.commandAsLsBlocks("lsinfo")).thenReturn(List.of());

        mockMvc.perform(get("/MPDServlet").param("action", "browse").param("uri", ""))
            .andExpect(status().isOk());

        // Must be plain "lsinfo", NOT 'lsinfo ""' — a subtly different command
        verify(client).commandAsLsBlocks("lsinfo");
        verify(client, never()).commandAsLsBlocks(argThat(s -> s != null && s.contains("\"")));
    }

    @Test
    void search_withBlankQuery_shortCircuitsWithoutTouchingMpdAtAll() throws Exception {
        MPDClient client = mockClient();

        mockMvc.perform(get("/MPDServlet").param("action", "search").param("q", "   "))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"));

        verify(client).close();
        verifyNoMoreInteractions(client);
    }
}
