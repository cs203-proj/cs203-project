package com.cs203.healthwatch.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.cs203.healthwatch.dto.ProfileRequest;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The codes are frozen for CG-154 / CG-22. This keeps the enums and the Swagger documentation in step. */
class ProfileCodesTest {

    @Test
    void wireFormatIsFrozen() {
        assertThat(Region.codes()).containsExactly("central", "north", "south", "east", "west");
        assertThat(Audience.codes()).containsExactly("GENERAL_PUBLIC", "VULNERABLE", "HEALTHCARE_WORKER");
    }

    @Test
    void swaggerDocumentsTheSameCodes() throws Exception {
        var region = ProfileRequest.class.getDeclaredField("region").getAnnotation(Schema.class);
        var audience = ProfileRequest.class.getDeclaredField("audience").getAnnotation(Schema.class);
        var conditions = ProfileRequest.class.getDeclaredField("healthConditions").getAnnotation(ArraySchema.class);

        assertThat(List.of(region.allowableValues())).containsExactlyElementsOf(Region.codes());
        assertThat(List.of(audience.allowableValues())).containsExactlyElementsOf(Audience.codes());
        assertThat(List.of(conditions.schema().allowableValues())).containsExactlyElementsOf(HealthCondition.codes());
    }
}
