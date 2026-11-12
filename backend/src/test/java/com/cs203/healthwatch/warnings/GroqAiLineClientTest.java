package com.cs203.healthwatch.warnings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.explanation.Assessment;
import com.cs203.healthwatch.explanation.ExplanationProperties;
import com.cs203.healthwatch.profile.Audience;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** CG-154: the AI line client against a mock HTTP server, never the live API. */
class GroqAiLineClientTest {

    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";

    private final ObjectMapper mapper = new ObjectMapper();
    private final AiLineContext context = new AiLineContext(Audience.VULNERABLE, "east", "pm25",
            WarningSeverity.HIGH, ConfidenceLevel.MEDIUM, Assessment.GENUINE_TREND, "Consistent with regional haze");
    private MockRestServiceServer server;
    private GroqAiLineClient client;

    private static ExplanationProperties props(String apiKey) {
        return new ExplanationProperties(true, apiKey, "groq-test-model", "https://api.groq.com/openai/v1",
                Duration.ofSeconds(5), Duration.ofHours(6), Duration.ofMinutes(60), Duration.ofHours(2), 12,
                new ExplanationProperties.Retry(Duration.ofMinutes(5), 5, 3));
    }

    @BeforeEach
    void setUp() {
        ExplanationProperties props = props("test-key");
        RestClient.Builder builder = RestClient.builder().baseUrl(props.baseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        client = GroqAiLineClient.withRestClient(props, builder.build(), mapper);
    }

    private String groqResponse(String content) throws Exception {
        var message = Map.of("role", "assistant", "content", content);
        var choice = Map.of("index", 0, "message", message, "finish_reason", "stop");
        return mapper.writeValueAsString(Map.of("choices", List.of(choice)));
    }

    @Test
    void sendsBearerKeyStrictSchemaAndFacts_andReturnsTheLine() throws Exception {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("groq-test-model"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.messages[1].content").value(containsString("Audience: vulnerable groups")))
                .andExpect(jsonPath("$.messages[1].content").value(containsString("Region of Singapore: east")))
                .andExpect(jsonPath("$.messages[1].content").value(containsString("assessment of the event: "
                        + "GENUINE_TREND")))
                .andExpect(jsonPath("$.messages[1].content")
                        .value(containsString("Likely cause (hypothesis): Consistent with regional haze")))
                .andExpect(jsonPath("$.response_format.json_schema.schema.required[0]").value("whyItMatters"))
                .andRespond(withSuccess(groqResponse("{\"whyItMatters\":\"Haze can worsen asthma.\"}"),
                        MediaType.APPLICATION_JSON));

        assertThat(client.write(context)).isEqualTo("Haze can worsen asthma.");
        server.verify();
    }

    @Test
    void promptForbidsAdviceAndClaimsAboutTheReader() {
        assertThat(AiLinePrompt.SYSTEM_INSTRUCTION)
                .contains("Never give advice, actions, instructions or recommendations")
                .contains("never say or imply you know a reader's own conditions");
        assertThat(AiLinePrompt.facts(context)).doesNotContain("asthma", "COPD", "PREGNANCY");
    }

    @Test
    void missingKeyFailsWithoutCallingTheApi() {
        GroqAiLineClient unconfigured = GroqAiLineClient.withRestClient(props(""), RestClient.create(), mapper);

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.write(context)).hasMessageContaining("not configured");
    }

    @Test
    void httpErrorBecomesAnExceptionWithoutTheKey() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.write(context))
                .hasMessageContaining("Groq call failed")
                .hasMessageNotContaining("test-key");
    }

    @Test
    void emptyOrMalformedRepliesAreRejected() throws Exception {
        assertThatThrownBy(() -> client.parse(groqResponse("{\"whyItMatters\":\"  \"}")))
                .hasMessageContaining("'whyItMatters'");
        assertThatThrownBy(() -> client.parse(groqResponse("not json"))).hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> client.parse("{\"choices\":[]}")).hasMessageContaining("no answer");
    }
}
