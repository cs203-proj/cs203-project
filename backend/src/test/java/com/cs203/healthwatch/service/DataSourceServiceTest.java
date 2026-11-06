package com.cs203.healthwatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cs203.healthwatch.common.exception.ConflictException;
import com.cs203.healthwatch.common.exception.ResourceNotFoundException;
import com.cs203.healthwatch.dto.CreateDataSourceRequest;
import com.cs203.healthwatch.dto.DataSourceResponse;
import com.cs203.healthwatch.dto.UpdateDataSourceRequest;
import com.cs203.healthwatch.ingestion.sources.DataSource;
import com.cs203.healthwatch.ingestion.sources.DataSourceRepository;
import com.cs203.healthwatch.ingestion.IngestionService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** CG-58 business rules. The repository is mocked. */
@ExtendWith(MockitoExtension.class)
class DataSourceServiceTest {

    private static final UUID ID = UUID.fromString("5f1e2d3c-4b5a-4968-8776-655443322110");

    @Mock DataSourceRepository repo;
    DataSourceService service;

    /**
     * Built by hand rather than with @InjectMocks: the service takes a List of IngestionService beans, which
     * @InjectMocks cannot supply. This "build" has ingestion code for datagovsg_pm25 only.
     */
    @BeforeEach
    void setUp() {
        IngestionService pm = mock(IngestionService.class);
        when(pm.sourceName()).thenReturn("datagovsg_pm25");
        service = new DataSourceService(repo, List.of(pm));
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
                new CreateDataSourceRequest(" nea_psi ", "air_quality", " https://example.test/psi "));

        assertThat(created.name()).isEqualTo("nea_psi");
        assertThat(created.url()).isEqualTo("https://example.test/psi");
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
        when(repo.saveAndFlush(any(DataSource.class))).thenThrow(new DataIntegrityViolationException("unique"));

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
        DataSourceResponse updated = service.update(ID, new UpdateDataSourceRequest("https://example.test/pm25"));

        // Assert
        assertThat(updated.url()).isEqualTo("https://example.test/pm25");
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
}
