package com.cs203.healthwatch.ingestion.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class DataSourceRegistryTest {

    private static final String NAME = "data.gov.sg PM2.5";
    private static final String TYPE = "AIR_QUALITY";

    @Mock
    private DataSourceRepository repo;

    @InjectMocks
    private DataSourceRegistry registry;

    private DataSource source(UUID id) {
        DataSource s = new DataSource(NAME, TYPE);
        s.setId(id);
        return s;
    }

    @Test
    void resolve_existingSource_isReusedNotCreated() {
        UUID id = UUID.randomUUID();
        when(repo.findByName(NAME)).thenReturn(Optional.of(source(id)));

        assertThat(registry.resolve(NAME, TYPE)).isEqualTo(id);
        verify(repo, never()).save(any());
    }

    @Test
    void resolve_newSource_isCreatedWithNameAndType() {
        UUID id = UUID.randomUUID();
        when(repo.findByName(NAME)).thenReturn(Optional.empty());
        when(repo.save(any(DataSource.class))).thenReturn(source(id));

        assertThat(registry.resolve(NAME, TYPE)).isEqualTo(id);

        ArgumentCaptor<DataSource> saved = ArgumentCaptor.forClass(DataSource.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo(NAME);
        assertThat(saved.getValue().getType()).isEqualTo(TYPE);
    }

    @Test
    void resolve_calledTwice_hitsDatabaseOnlyOnce() {
        UUID id = UUID.randomUUID();
        when(repo.findByName(NAME)).thenReturn(Optional.of(source(id)));

        registry.resolve(NAME, TYPE);
        UUID second = registry.resolve(NAME, TYPE);

        assertThat(second).isEqualTo(id);
        verify(repo, times(1)).findByName(NAME);
    }

    @Test
    void resolve_concurrentInsertRace_fallsBackToExistingRow() {
        UUID winnerId = UUID.randomUUID();
        when(repo.findByName(NAME))
                .thenReturn(Optional.empty())                 // our lookup: not there yet
                .thenReturn(Optional.of(source(winnerId)));   // after the other insert won
        when(repo.save(any(DataSource.class))).thenThrow(new DataIntegrityViolationException("duplicate name"));

        assertThat(registry.resolve(NAME, TYPE)).isEqualTo(winnerId);
    }
}
