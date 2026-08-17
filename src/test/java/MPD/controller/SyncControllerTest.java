package MPD.controller;

import MPD.service.MpdService;
import MPD.service.SyncService;
import MPD.service.SyncService.PlaylistEntry;
import MPD.service.SyncService.SyncJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SyncController.class)
class SyncControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired SyncController syncController; // for direct calls in the SSE edge-case tests below

    @MockBean SyncService syncService;
    @MockBean MpdService  mpdService;

    // ── GET ───────────────────────────────────────────────────────────────────

    @Test
    void list_returnsSyncedPlaylistsFromService() throws Exception {
        when(syncService.listSyncedPlaylists()).thenReturn(
            List.of(new PlaylistEntry("My Playlist", "https://youtube.com/x", "2026-01-01 00:00:00", 12))
        );

        mockMvc.perform(get("/SyncServlet").param("action", "list"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("My Playlist"))
            .andExpect(jsonPath("$[0].tracks").value(12));
    }

    @Test
    void unknownGetAction_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/SyncServlet").param("action", "bogus"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Unknown action: bogus"));
    }

    // ── POST sync ─────────────────────────────────────────────────────────────

    @Test
    void sync_withBlankUrl_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/SyncServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"sync\",\"url\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("url is required"));

        verifyNoInteractions(syncService);
    }

    // ── POST updateMPD — the ok:false-with-HTTP-200 contract ─────────────────

    @Test
    void updateMpd_success_returnsOkTrue() throws Exception {
        mockMvc.perform(post("/SyncServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"updateMPD\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void updateMpd_whenMpdCommandThrows_stillReturnsHttp200WithOkFalse() throws Exception {
        doThrow(new RuntimeException("MPD unreachable")).when(mpdService).command("update");

        mockMvc.perform(post("/SyncServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"updateMPD\"}"))
            .andExpect(status().isOk())  // NOT 500 — this is the point
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.error").value("MPD unreachable"));
    }

    @Test
    void unknownPostAction_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/SyncServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"bogus\"}"))
            .andExpect(status().isBadRequest());
    }

    // ── SSE stream — early-return edge cases (no async simulation needed) ────

    @Test
    void stream_withNullJobId_completesImmediatelyWithoutError() {
        Object result = syncController.get("stream", null);
        assertInstanceOf(SseEmitter.class, result);
    }

    @Test
    void stream_withUnknownJobId_completesImmediatelyWithoutError() {
        when(syncService.getJob("does-not-exist")).thenReturn(null);
        Object result = syncController.get("stream", "does-not-exist");
        assertInstanceOf(SseEmitter.class, result);
    }
}
