package com.cs203.healthwatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cs203.healthwatch.common.exception.BadRequestException;
import com.cs203.healthwatch.common.exception.ConflictException;
import com.cs203.healthwatch.common.exception.ResourceNotFoundException;
import com.cs203.healthwatch.dto.CreateDataSourceRequest;
import com.cs203.healthwatch.dto.DataSourceResponse;
import com.cs203.healthwatch.dto.UpdateDataSourceRequest;
import com.cs203.healthwatch.ingestion.sources.DataSource;
import com.cs203.healthwatch.ingestion.sources.DataSourceRepository;
import com.cs203.healthwatch.ingestion.IngestionService;
import com.cs203.healthwatch.ingestion.config.SourceUrlProperties;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** CG-58 business rules. The repository is mocked. */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DataSourceServiceTest {

    private static final UUID ID = UUID.fromString("5f1e2d3c-4b5a-4968-8776-655443322110");
    private static final String ALLOWED_URL = "https://api-open.data.gov.sg/v2/real-time/api/pm25";

    @Mock DataSourceRepository repo;
    DataSourceService service;

    /**
     * Built by hand rather than with @InjectMocks: the service takes a List of IngestionService beans, which
     * @InjectMocks cannot supply. This "build" has ingestion code for datagovsg_pm25 (type air_quality) only, and
     * source urls may only point at api-open.data.gov.sg.
     */
    @BeforeEach
    void setUp() {
        IngestionService pm = mock(IngestionService.class);
        when(pm.sourceName()).thenReturn("datagovsg_pm25");
        when(pm.sourceType()).thenReturn("air_quality");
        service = new DataSourceService(repo, List.of(pm), new SourceUrlProperties(List.of("api-open.data.gov.sg")));
    }

    private static DataIntegrityViolationException violation(String sqlState) {
        return new DataIntegrityViolationException("constraint", new SQLException("constraint", sqlState));
    }

    private static DataSource source(String name) {
        DataSource s = new DataSource(name, "air_quality");
        s.setId(ID);
        return s;
    }

    @Test
    void create_savesADisabledSource_withTrimmedFields() {
        when(repo.existsByName("nea_psi")).thenReturn(false);
        when(repo.saveAndFlush(any(DataSource.class))).thenAnswer(inv -> inv.getArgument(0));

        DataSourceResponse created = service.create(
                new CreateDataSourceRequest(" nea_psi ", "air_quality", " https://api-open.data.gov.sg/v2/real-time/api/psi "));

        assertThat(created.name()).isEqualTo("nea_psi");
        assertThat(created.url()).isEqualTo("https://api-open.data.gov.sg/v2/real-time/api/psi");
        assertThat(created.enabled()).as("registered sources start disabled").isFalse();
        assertThat(created.ingestionAvailable()).as("no ingestion code for nea_psi in this build").isFalse();
    }

    @Test
    void get_sourceWithIngestionCode_isReportedAsAvailable() {
        when(repo.findById(ID)).thenReturn(Optional.of(source("datagovsg_pm25")));

        assertThat(service.get(ID).ingestionAvailable()).isTrue();
    }

    @Test
    void create_duplicateName_isConflict_andNothingIsSaved() {
        when(repo.existsByName("datagovsg_pm25")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateDataSourceRequest("datagovsg_pm25", "air_quality", null)))
                .isInstanceOf(ConflictException.class);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void create_duplicateInsertedConcurrently_isStillConflict() {
        when(repo.existsByName("nea_psi")).thenReturn(false);
        when(repo.saveAndFlush(any(DataSource.class))).thenThrow(violation("23505"));

        assertThatThrownBy(() -> service.create(new CreateDataSourceRequest("nea_psi", "air_quality", null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void list_byName_returnsOnlyThatSource() {
        when(repo.findByName("datagovsg_pm25")).thenReturn(Optional.of(source("datagovsg_pm25")));

        assertThat(service.list(" datagovsg_pm25 ")).extracting(DataSourceResponse::name)
                .containsExactly("datagovsg_pm25");
    }

    @Test
    void list_unknownName_isEmpty_not404() {
        when(repo.findByName("nope")).thenReturn(Optional.empty());

        assertThat(service.list("nope")).isEmpty();
    }

    @Test
    void get_unknownId_isNotFound() {
        when(repo.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_setsUrl_andKeepsTheName() {
        // Arrange
        DataSource s = source("datagovsg_pm25");
        when(repo.findById(ID)).thenReturn(Optional.of(s));

        // Act
        DataSourceResponse updated = service.update(ID, new UpdateDataSourceRequest(ALLOWED_URL));

        // Assert
        assertThat(updated.url()).isEqualTo(ALLOWED_URL);
        assertThat(updated.name()).as("name never changes").isEqualTo("datagovsg_pm25");
    }

    @Test
    void update_blankUrl_clearsItSoConfigIsUsed() {
        // Arrange
        DataSource s = source("datagovsg_pm25");
        s.setUrl("https://old.example.test/pm25");
        when(repo.findById(ID)).thenReturn(Optional.of(s));

        // Act
        DataSourceResponse updated = service.update(ID, new UpdateDataSourceRequest("  "));

        // Assert
        assertThat(updated.url()).isNull();
    }

    @Test
    void create_blankUrl_isStoredAsNull() {
        // Arrange
        when(repo.existsByName("nea_psi")).thenReturn(false);
        when(repo.saveAndFlush(any(DataSource.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        DataSourceResponse created = service.create(new CreateDataSourceRequest("nea_psi", "air_quality", " "));

        // Assert
        assertThat(created.url()).isNull();
    }

    @Test
    void setEnabled_false_disablesWithoutDeleting() {
        // Arrange
        when(repo.findById(ID)).thenReturn(Optional.of(source("datagovsg_pm25")));

        // Act
        DataSourceResponse result = service.setEnabled(ID, false);

        // Assert
        assertThat(result.enabled()).isFalse();
        verify(repo, never()).delete(any());
    }

    @Test
    void setEnabled_true_reEnablesADisabledSource() {
        // Arrange
        DataSource s = source("datagovsg_pm25");
        s.setEnabled(false);
        when(repo.findById(ID)).thenReturn(Optional.of(s));

        // Act
        DataSourceResponse result = service.setEnabled(ID, true);

        // Assert
        assertThat(result.enabled()).isTrue();
    }

    // ------------------------------------------------------------------ review: SSRF

    @ParameterizedTest
    @ValueSource(strings = {
            "https://localhost/pm25",
            "https://127.0.0.1/pm25",
            "https://169.254.169.254/latest/meta-data",
            "https://10.0.0.5/pm25",
            "https://192.168.1.10/pm25",
            "https://api-open.data.gov.sg.evil.test/pm25",
            "https://api-open.data.gov.sg@evil.test/pm25",
            "https://api-open.data.gov.sg:8443/pm25",
            "http://api-open.data.gov.sg/pm25"
    })
    void update_urlNotHttpsOnAnAllowedHost_isRejected(String url) {
        // Arrange
        DataSource s = source("datagovsg_pm25");
        when(repo.findById(ID)).thenReturn(Optional.of(s));

        // Act + Assert
        assertThatThrownBy(() -> service.update(ID, new UpdateDataSourceRequest(url)))
                .isInstanceOf(BadRequestException.class);
        assertThat(s.getUrl()).as("url left unchanged").isNull();
    }

    @Test
    void update_allowedHost_isAccepted_caseInsensitively() {
        // Arrange
        when(repo.findById(ID)).thenReturn(Optional.of(source("datagovsg_pm25")));

        // Act
        DataSourceResponse updated = service.update(ID,
                new UpdateDataSourceRequest("https://API-OPEN.data.gov.sg/v2/real-time/api/pm25"));

        // Assert
        assertThat(updated.url()).isEqualTo("https://API-OPEN.data.gov.sg/v2/real-time/api/pm25");
    }

    @Test
    void create_urlOnDisallowedHost_isRejected_andNothingIsSaved() {
        // Arrange
        when(repo.existsByName("nea_psi")).thenReturn(false);

        // Act + Assert
        assertThatThrownBy(() -> service.create(
                new CreateDataSourceRequest("nea_psi", "air_quality", "https://localhost:8080/actuator")))
                .isInstanceOf(BadRequestException.class);
        verify(repo, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------ review: url not logged

    @Test
    void update_doesNotWriteTheUrlToTheLog(CapturedOutput output) {
        // Arrange
        when(repo.findById(ID)).thenReturn(Optional.of(source("datagovsg_pm25")));

        // Act
        service.update(ID, new UpdateDataSourceRequest(ALLOWED_URL + "?api_key=SECRET123"));

        // Assert
        assertThat(output).contains("data source datagovsg_pm25 url updated");
        assertThat(output).doesNotContain("SECRET123");
    }

    // ------------------------------------------------------------------ review: name and type

    @Test
    void create_ingestionNameWithADifferentType_isRejected() {
        // Arrange: fresh database, the ingestion code has not created its row yet
        when(repo.existsByName("datagovsg_pm25")).thenReturn(false);

        // Act + Assert
        assertThatThrownBy(() -> service.create(new CreateDataSourceRequest("datagovsg_pm25", "weather", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("air_quality");
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void get_ingestionNameWithADifferentType_isNotReportedAsAvailable() {
        // Arrange
        DataSource wrongType = new DataSource("datagovsg_pm25", "weather");
        wrongType.setId(ID);
        when(repo.findById(ID)).thenReturn(Optional.of(wrongType));

        // Act
        DataSourceResponse result = service.get(ID);

        // Assert
        assertThat(result.ingestionAvailable()).isFalse();
    }

    // ------------------------------------------------------------------ review: integrity violations

    @Test
    void create_otherConstraintViolation_isNotReportedAsADuplicate() {
        // Arrange: e.g. a NOT NULL violation (SQLState 23502)
        when(repo.existsByName("nea_psi")).thenReturn(false);
        when(repo.saveAndFlush(any(DataSource.class))).thenThrow(violation("23502"));

        // Act + Assert
        assertThatThrownBy(() -> service.create(new CreateDataSourceRequest("nea_psi", "air_quality", null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(ConflictException.class);
    }
}
