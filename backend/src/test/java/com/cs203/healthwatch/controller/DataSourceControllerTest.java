package com.cs203.healthwatch.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs203.healthwatch.common.exception.ConflictException;
import com.cs203.healthwatch.common.exception.ResourceNotFoundException;
import com.cs203.healthwatch.dto.CreateDataSourceRequest;
import com.cs203.healthwatch.dto.DataSourceResponse;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtService;
import com.cs203.healthwatch.security.SecurityConfig;
import com.cs203.healthwatch.service.DataSourceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Web layer only: status codes, validation and admin-only access. DataSourceService is mocked. */
@WebMvcTest(DataSourceController.class)
@Import({SecurityConfig.class, JwtService.class})
class DataSourceControllerTest {

    private static final UUID ID = UUID.fromString("5f1e2d3c-4b5a-4968-8776-655443322110");
    private static final DataSourceResponse PM25 = new DataSourceResponse(ID, "datagovsg_pm25", "air_quality", null,
            true, true, Instant.parse("2026-09-24T03:15:00Z"));

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @MockBean DataSourceService service;
    @MockBean UserRepository userRepository;

    private String adminToken;
    private String viewerToken;

    @BeforeEach
    void users() {
        User admin = new User("admin", "hash", "admin");
        admin.setId(UUID.randomUUID());
        User viewer = new User("viewer", "hash", "viewer");
        viewer.setId(UUID.randomUUID());
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(userRepository.findByUsername("viewer")).thenReturn(Optional.of(viewer));
        adminToken = jwtService.generateToken("admin", "admin");
        viewerToken = jwtService.generateToken("viewer", "viewer");
    }

    @Test
    void list_asAdmin_returnsSources() throws Exception {
        when(service.list(null)).thenReturn(List.of(PM25));

        mockMvc.perform(get("/data-sources").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("datagovsg_pm25"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].ingestionAvailable").value(true));
    }

    @Test
    void list_byName_passesTheNameThrough() throws Exception {
        when(service.list("datagovsg_pm25")).thenReturn(List.of(PM25));

        mockMvc.perform(get("/data-sources").param("name", "datagovsg_pm25")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ID.toString()));
    }

    @Test
    void withoutToken_is401() throws Exception {
        mockMvc.perform(get("/data-sources")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void nonAdmin_is403() throws Exception {
        mockMvc.perform(get("/data-sources").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void get_unknownId_is404() throws Exception {
        when(service.get(ID)).thenThrow(new ResourceNotFoundException("Data source " + ID + " not found"));

        mockMvc.perform(get("/data-sources/" + ID).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_returns201() throws Exception {
        when(service.create(any(CreateDataSourceRequest.class))).thenReturn(PM25);

        mockMvc.perform(post("/data-sources").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"datagovsg_pm25\",\"type\":\"air_quality\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ID.toString()));
    }

    @Test
    void create_invalidUrl_is400() throws Exception {
        mockMvc.perform(post("/data-sources").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"type\":\"air_quality\",\"url\":\"not a url\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void create_nameOf100Characters_is201() throws Exception {
        when(service.create(any(CreateDataSourceRequest.class))).thenReturn(PM25);

        mockMvc.perform(post("/data-sources").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "a".repeat(100) + "\",\"type\":\"air_quality\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void create_nameOf101Characters_is400() throws Exception {
        mockMvc.perform(post("/data-sources").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "a".repeat(101) + "\",\"type\":\"air_quality\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void create_nonHttpUrl_is400() throws Exception {
        mockMvc.perform(post("/data-sources").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"type\":\"air_quality\",\"url\":\"ftp://example.test/pm25\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void update_invalidUrl_is400() throws Exception {
        mockMvc.perform(put("/data-sources/" + ID).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"not a url\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void create_missingName_is400() throws Exception {
        mockMvc.perform(post("/data-sources").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"air_quality\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_duplicateName_is409() throws Exception {
        when(service.create(any(CreateDataSourceRequest.class)))
                .thenThrow(new ConflictException("A data source named 'datagovsg_pm25' already exists"));

        mockMvc.perform(post("/data-sources").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"datagovsg_pm25\",\"type\":\"air_quality\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void disable_returnsTheUpdatedSource() throws Exception {
        DataSourceResponse disabled = new DataSourceResponse(ID, "datagovsg_pm25", "air_quality", null, false,
                true, PM25.createdAt());
        when(service.setEnabled(ID, false)).thenReturn(disabled);

        mockMvc.perform(patch("/data-sources/" + ID + "/enabled").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        verify(service).setEnabled(ID, false);
    }

    @Test
    void disable_withoutEnabledField_is400() throws Exception {
        mockMvc.perform(patch("/data-sources/" + ID + "/enabled").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void thereIsNoDelete() throws Exception {
        mockMvc.perform(delete("/data-sources/" + ID).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isMethodNotAllowed());
    }
}
