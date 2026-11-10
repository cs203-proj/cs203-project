package com.cs203.healthwatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "The signed-in user's active warnings, most severe first, then newest first")
public record MyWarningListResponse(
        List<MyWarningResponse> items,
        @Schema(description = "Set when there is something the user should do, e.g. finish their profile; null otherwise",
                example = "Complete your profile (region and audience) to see warnings that apply to you.")
        String message
) {
}
