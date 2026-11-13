package com.cs203.healthwatch;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs203.healthwatch.ingestion.FetchClient;
import com.cs203.healthwatch.ingestion.pm.PmIngestionService;
import com.cs203.healthwatch.ingestion.pm.PmSourceProperties;
import com.cs203.healthwatch.ingestion.sources.DataSource;
import com.cs203.healthwatch.ingestion.sources.DataSourceRegistry;
import com.cs203.healthwatch.ingestion.sources.DataSourceRepository;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.security.JwtService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CG-58 end to end: an admin changes a data source over HTTP (real JWT, real security, in-memory database) and the
 * next ingestion pull follows that change. Only the network call to data.gov.sg is mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataSourceIngestionIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired DataSourceRegistry registry;
    @Autowired DataSourceRepository dataSourceRepository;
    @Autowired PmIngestionService ingestion;
    @Autowired PmSourceProperties pm;
    @MockBean FetchClient fetchClient;

    private String adminToken;
    private UUID sourceId;

    @BeforeEach
    void adminAndEnabledSource() {
        User admin = userRepository.findByUsername("admin")
                .orElseGet(() -> userRepository.save(new User("admin", "hash", "admin")));
        adminToken = jwtService.generateToken(admin.getId(), "admin", "admin");
        // the row ingestion uses; created on first use like on a fresh database
        sourceId = registry.current(pm.name(), pm.type()).getId();
    }

    /** Leave the shared row enabled with no url, so other tests see the default state. */
    @AfterEach
    void resetSource() {
        DataSource row = dataSourceRepository.findById(sourceId).orElseThrow();
        row.setEnabled(true);
        row.setUrl(null);
        dataSourceRepository.save(row);
    }

    @Test
    void disablingThroughTheApi_stopsTheNextPull() throws Exception {
        // Arrange
        mockMvc.perform(patch("/data-sources/{id}/enabled", sourceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());

        // Act
        ingestion.ingestLatest();

        // Assert
        verify(fetchClient, never()).fetch(anyString());
    }

    @Test
    void reEnablingThroughTheApi_resumesPulls() throws Exception {
        // Arrange
        DataSource row = dataSourceRepository.findById(sourceId).orElseThrow();
        row.setEnabled(false);
        dataSourceRepository.save(row);
        mockMvc.perform(patch("/data-sources/{id}/enabled", sourceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        when(fetchClient.fetch(anyString())).thenReturn(null);   // a failed pull; we only check it was attempted

        // Act
        ingestion.ingestLatest();

        // Assert
        verify(fetchClient).fetch(pm.url());
    }

    @Test
    void urlSetThroughTheApi_isUsedByTheNextPull() throws Exception {
        // Arrange
        String newUrl = "https://api-open.data.gov.sg/v2/real-time/api/pm25?mirror=1";   // allowed host
        mockMvc.perform(put("/data-sources/{id}", sourceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + newUrl + "\"}"))
                .andExpect(status().isOk());
        when(fetchClient.fetch(anyString())).thenReturn(null);

        // Act
        ingestion.ingestLatest();

        // Assert
        verify(fetchClient).fetch(newUrl);
        verify(fetchClient, never()).fetch(pm.url());
    }
}
