package com.cs203.healthwatch.controller;

import com.cs203.healthwatch.common.ApiErrorResponse;
import com.cs203.healthwatch.dto.CreateDataSourceRequest;
import com.cs203.healthwatch.dto.DataSourceResponse;
import com.cs203.healthwatch.dto.SetDataSourceEnabledRequest;
import com.cs203.healthwatch.dto.UpdateDataSourceRequest;
import com.cs203.healthwatch.service.DataSourceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;

import jakarta.validation.Valid;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/data-sources")
@RequiredArgsConstructor
@Tag(name = "Data sources", description = "Manage data sources, their feed URL and whether they are ingested (admin only)")
public class DataSourceController {

    private final DataSourceService service;

    @GetMapping
    @Operation(summary = "List data sources",
            description = "All sources, enabled and disabled, sorted by name. Pass name to filter by exact name; "
                + "no match returns an empty list.")
    @ApiResponse(responseCode = "200", description = "The sources (empty if name matches none)")
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Token is valid but the user is not an admin", content = @Content)
    public List<DataSourceResponse> list(
            @Parameter(description = "Optional. Exact source name (omit to list all sources)", example = "datagovsg_pm25")
            @RequestParam(required = false) String name) {
        return service.list(name);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a data source")
    @ApiResponse(responseCode = "200", description = "The source")
    @ApiResponse(responseCode = "404", description = "No source with this id",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Token is valid but the user is not an admin", content = @Content)
    public DataSourceResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a data source",
            description = "Registers a source, disabled, so nothing is pulled until an admin enables it with "
                    + "PATCH /data-sources/{id}/enabled. ingestionAvailable shows whether this build has ingestion "
                    + "code for the name.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "Missing name or type, or invalid url",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "A source with this name already exists",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Token is valid but the user is not an admin", content = @Content)
    public DataSourceResponse create(@Valid @RequestBody CreateDataSourceRequest request) {
        return service.create(request);
    }
    // Name and type cannot change, because ingestion finds its source by name and readings are grouped by type.
    @PutMapping("/{id}")
    @Operation(summary = "Edit a data source's URL",
            description = "Sets the feed URL used from the next pull. Null falls back to the configured default. "
                    + "The URL must serve the same response format as before."
                    + " A wrong URL does not fall back to the default: failed pulls are logged and unusable responses are stored as malformed readings.")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "400", description = "Invalid url",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No source with this id",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Token is valid but the user is not an admin", content = @Content)
    public DataSourceResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateDataSourceRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/enabled")
    @Operation(summary = "Disable or re-enable a data source",
            description = "Disabled sources are skipped from the next scheduled pull, and backfill for them "
                    + "returns 409. Existing readings are kept.")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "400", description = "Body missing or enabled not true/false",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No source with this id",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Token is valid but the user is not an admin", content = @Content)
    public DataSourceResponse setEnabled(@PathVariable UUID id,
                                         @Valid @RequestBody SetDataSourceEnabledRequest request) {
        return service.setEnabled(id, request.enabled());
    }
}
