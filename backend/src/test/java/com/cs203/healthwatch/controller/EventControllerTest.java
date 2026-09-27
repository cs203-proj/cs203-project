package com.cs203.healthwatch.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs203.healthwatch.dto.EventResponse;
import com.cs203.healthwatch.model.EventStatus;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtService;
import com.cs203.healthwatch.service.EventReader;
import com.cs203.healthwatch.service.EventService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

// Web-layer tests against a mocked EventReader: no database. Security filters are switched off because the
// admin-role setup (401/403) is not in this branch yet; see the note at the bottom of this class.
@WebMvcTest(EventController.class)
@Import(EventService.class)
@AutoConfigureMockMvc(addFilters = false)
class EventControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean EventReader eventReader;
    @MockBean JwtService jwtService;
    @MockBean UserRepository userRepository;

    private static EventResponse event(double deviation, EventStatus status) {
        return new EventResponse(UUID.randomUUID(), Instant.parse("2026-09-24T03:00:00Z"),
                "AQI", "Singapore", status, deviation);
    }

    // --- sort order ---

    @Test
    void returnsEventsInTheOrderTheReaderGivesThem_largestDeviationFirst() throws Exception {
        when(eventReader.find(any(), anyLong(), anyInt())).thenReturn(new EventReader.Result(
                List.of(event(5.7, EventStatus.NEW), event(4.2, EventStatus.UNDER_REVIEW), event(3.1, EventStatus.NEW)),
                3));

        mockMvc.perform(get("/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].deviationSize").value(5.7))
                .andExpect(jsonPath("$.items[1].deviationSize").value(4.2))
                .andExpect(jsonPath("$.items[2].deviationSize").value(3.1))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void returnsTheFieldsTheAdminNeeds() throws Exception {
        when(eventReader.find(any(), anyLong(), anyInt())).thenReturn(
                new EventReader.Result(List.of(event(4.2, EventStatus.NEW)), 1));

        mockMvc.perform(get("/events"))
                .andExpect(jsonPath("$.items[0].id").exists())
                .andExpect(jsonPath("$.items[0].timestamp").value("2026-09-24T03:00:00Z"))
                .andExpect(jsonPath("$.items[0].signalType").value("AQI"))
                .andExpect(jsonPath("$.items[0].region").value("Singapore"))
                .andExpect(jsonPath("$.items[0].status").value("NEW"))
                .andExpect(jsonPath("$.items[0].deviationSize").value(4.2));
    }

    // --- status filter ---

    @Test
    void passesTheStatusFilterToTheReader() throws Exception {
        when(eventReader.find(any(), anyLong(), anyInt())).thenReturn(new EventReader.Result(List.of(), 0));

        mockMvc.perform(get("/events").param("status", "NEW")).andExpect(status().isOk());

        verify(eventReader).find(eq(EventStatus.NEW), eq(0L), eq(50));
    }

    @Test
    void statusFilterIsCaseInsensitive() throws Exception {
        when(eventReader.find(any(), anyLong(), anyInt())).thenReturn(new EventReader.Result(List.of(), 0));

        mockMvc.perform(get("/events").param("status", "under_review")).andExpect(status().isOk());

        verify(eventReader).find(eq(EventStatus.UNDER_REVIEW), eq(0L), eq(50));
    }

    @Test
    void noStatusFilterMeansAllStatuses() throws Exception {
        when(eventReader.find(any(), anyLong(), anyInt())).thenReturn(new EventReader.Result(List.of(), 0));

        mockMvc.perform(get("/events")).andExpect(status().isOk());

        verify(eventReader).find(eq(null), eq(0L), eq(50));
    }

    @Test
    void rejectsUnknownStatusWithClearMessage() throws Exception {
        mockMvc.perform(get("/events").param("status", "FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("Unknown status 'FOO'")))
                .andExpect(jsonPath("$.message").value(containsString("NEW, UNDER_REVIEW, CONFIRMED, DISMISSED")));

        verifyNoInteractions(eventReader);
    }

    // --- empty list ---

    @Test
    void returnsEmptyListWhenNoEvents() throws Exception {
        when(eventReader.find(any(), anyLong(), anyInt())).thenReturn(new EventReader.Result(List.of(), 0));

        mockMvc.perform(get("/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.total").value(0));
    }

    // --- pagination ---

    @Test
    void appliesDefaultLimitOf50AndOffsetOf0() throws Exception {
        when(eventReader.find(any(), anyLong(), anyInt())).thenReturn(new EventReader.Result(List.of(), 0));

        mockMvc.perform(get("/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.offset").value(0));

        verify(eventReader).find(eq(null), eq(0L), eq(50));
    }

    @Test
    void passesLimitAndOffsetToTheReader() throws Exception {
        when(eventReader.find(any(), anyLong(), anyInt())).thenReturn(new EventReader.Result(List.of(), 10));

        mockMvc.perform(get("/events").param("limit", "2").param("offset", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.offset").value(4))
                .andExpect(jsonPath("$.total").value(10));

        verify(eventReader).find(eq(null), eq(4L), eq(2));
    }

    @Test
    void rejectsOutOfRangeLimitAndOffset() throws Exception {
        mockMvc.perform(get("/events").param("limit", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/events").param("limit", "201")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/events").param("offset", "-1")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/events").param("limit", "abc")).andExpect(status().isBadRequest());

        verifyNoInteractions(eventReader);
    }

    // --- unauthorized ---
    // NOT COVERED YET: "no token gives 401 / non-admin gives 403" needs the admin role setup (CG-33), which is not
    // in this branch. Once it is merged, add tests that call /events without a token and with a non-admin token.
}
