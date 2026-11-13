package com.cs203.healthwatch.controller;

import com.cs203.healthwatch.consent.ConsentTerms;
import com.cs203.healthwatch.dto.ConsentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The consent notice shown before sign-up (CG-8). Public: it has to be readable before an account exists. */
@RestController
@RequestMapping("/consent")
@Tag(name = "Consent", description = "What health data is used for, and the version the user agrees to")
public class ConsentController {

    @GetMapping
    @Operation(
            summary = "Get the current consent notice",
            description = "Public. Returns the notice to show at sign-up and the version it is. The client sends "
                    + "that version back in POST /auth/register, so what the user agreed to is recorded exactly. "
                    + "Registering with an older version is rejected.")
    @ApiResponse(responseCode = "200", description = "The current notice",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ConsentResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "version": "1.0",
                              "text": "HealthWatch uses the information in your profile — your region, your audience group, and any health conditions you choose to tell us about — for one purpose only: to decide which public health warnings are relevant to you, and to word them for your situation.\\n\\nThis is not a medical service. Your health information is never used to diagnose you, to give you medical advice, or to make any decision about your care. If you are unwell, speak to a doctor.\\n\\nYour information is not sold, and it is not shared with advertisers.\\n\\nYou stay in control. You can change your profile at any time, and you can delete it. Deleting your profile removes your health information from HealthWatch."
                            }
                            """)))
    public ConsentResponse currentConsent() {
        return new ConsentResponse(ConsentTerms.VERSION, ConsentTerms.TEXT);
    }
}
