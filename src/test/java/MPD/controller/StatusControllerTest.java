package MPD.controller;

import MPD.DependencyManager;
import MPD.service.AudioWatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StatusController.class)
class StatusControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean  DependencyManager deps;
    @MockBean  AudioWatchService audio;

    // ── GET status ────────────────────────────────────────────────────────────

    @Test
    void get_returnsDependencyManagersStatusMapVerbatim() throws Exception {
        when(deps.statusMap()).thenReturn(Map.of(
            "ytdlp",  Map.of("ok", true, "version", "2026.01.01"),
            "mpd",    Map.of("ok", true),
            "ffmpeg", Map.of("ok", false)
        ));

        mockMvc.perform(get("/StatusServlet"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mpd.ok").value(true))
            .andExpect(jsonPath("$.ffmpeg.ok").value(false))
            .andExpect(jsonPath("$.ytdlp.version").value("2026.01.01"));
    }

    // ── POST updateYtDlp ──────────────────────────────────────────────────────

    @Test
    void updateYtDlp_wiresOutputAndVersionFromDependencyManager() throws Exception {
        when(deps.updateYtDlp()).thenReturn(List.of("Updated to 2026.02.01"));
        when(deps.getYtDlpVersion()).thenReturn("2026.02.01");

        mockMvc.perform(post("/StatusServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"updateYtDlp\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.version").value("2026.02.01"))
            .andExpect(jsonPath("$.output[0]").value("Updated to 2026.02.01"));
    }

    @Test
    void updateYtDlp_fallsBackToUnknownWhenVersionIsNull() throws Exception {
        when(deps.updateYtDlp()).thenReturn(List.of());
        when(deps.getYtDlpVersion()).thenReturn(null);

        mockMvc.perform(post("/StatusServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"updateYtDlp\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value("unknown"));
    }

    // ── POST calibrateSyncDelay ────────────────────────────────────────────────

    @Test
    void calibrateSyncDelay_passesExactDoubleValueThrough() throws Exception {
        mockMvc.perform(post("/StatusServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"calibrateSyncDelay\",\"delaySeconds\":2.4}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.delaySeconds").value(2.4));

        verify(audio).applySyncDelay(2.4);
    }

    @Test
    void calibrateSyncDelay_wholeNumberJsonValueStillCoercesToDoubleCorrectly() throws Exception {
        // JSON "3" (no decimal point) deserialises differently across
        // libraries — must still work via Number.doubleValue(), not just
        // when the value happens to already look like a double.
        mockMvc.perform(post("/StatusServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"calibrateSyncDelay\",\"delaySeconds\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        verify(audio).applySyncDelay(3.0);
    }

    @Test
    void calibrateSyncDelay_missingDelaySeconds_rejectsWithoutCallingApplySyncDelay() throws Exception {
        mockMvc.perform(post("/StatusServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"calibrateSyncDelay\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.error").value("delaySeconds is required and must be >= 0"));

        verify(audio, never()).applySyncDelay(anyDouble());
    }

    @Test
    void calibrateSyncDelay_whenApplyThrows_stillReturnsHttp200WithOkFalse() throws Exception {
        // CRITICAL contract test — this is the exact bug that was fixed on
        // the frontend: it must be possible to distinguish success from
        // failure via the `ok` field, because this endpoint always answers
        // with HTTP 200 regardless of whether the underlying operation
        // (writing PipeWire config, restarting services) actually succeeded.
        doThrow(new java.io.IOException("PipeWire restarted but sink never appeared"))
            .when(audio).applySyncDelay(2.5);

        mockMvc.perform(post("/StatusServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"calibrateSyncDelay\",\"delaySeconds\":2.5}"))
            .andExpect(status().isOk())  // NOT 500 — this is the point
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.error").value("PipeWire restarted but sink never appeared"));
    }

    @Test
    void unknownAction_returnsOkFalseNotHttpError() throws Exception {
        // Unlike SyncController/MpdController, StatusController's default
        // branch also just returns ok:false with 200 rather than a 400 — a
        // real, if minor, inconsistency across controllers. Documenting the
        // ACTUAL current behaviour here so a future harmonisation change is
        // a deliberate decision, not an accidental one.
        mockMvc.perform(post("/StatusServlet")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"bogus\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.error").value("Unknown action: bogus"));
    }
}
