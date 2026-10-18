package com.cs203.healthwatch.explanation;

import static com.cs203.healthwatch.explanation.TestExplanations.context;
import static com.cs203.healthwatch.explanation.TestExplanations.hourly;
import static com.cs203.healthwatch.explanation.TestExplanations.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The Gemini client against a mock HTTP server: no live API calls. */
class GeminiExplanationClientTest {

    private static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-test-model:generateContent";

    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private GeminiExplanationClient client;
    private ExplanationContext context;

    @BeforeEach
    void setUp() {
        ExplanationProperties props = props(true, "test-key");
        RestClient.Builder builder = RestClient.builder().baseUrl(props.baseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        client = GeminiExplanationClient.withRestClient(props, builder.build(), mapper);

        var readings = hourly(Instant.parse("2026-10-18T03:00:00Z"), 18, 20, 45, 55, 70);
        context = context(false, new DataQuality(false, "Based on partial data: 5 of 6 expected readings.", 5, 6),
                readings);
    }

    /** Wraps an answer the way generateContent does. */
    private String geminiResponse(String answerJson) throws Exception {
        var part = java.util.Map.of("text", answerJson);
        var content = java.util.Map.of("role", "model", "parts", java.util.List.of(part));
        var candidate = java.util.Map.of("content", content, "finishReason", "STOP");
        return mapper.writeValueAsString(java.util.Map.of("candidates", java.util.List.of(candidate)));
    }

    private static final String GOOD_ANSWER = """
            {"assessment":"GENUINE_TREND",
             "likelyCause":"Consistent with regional haze",
             "explanation":"PM2.5 rose steadily over three hours.",
             "evidence":["Last 3 readings 45, 55, 70 vs median 18", "Rise is gradual, not a single jump"],
             "assumptions":["Station is reporting correctly"]}
            """;

    @Test
    void sendsKeyInHeaderAndFactsAndSchema_andParsesTheAnswer() throws Exception {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andExpect(jsonPath("$.generationConfig.responseSchema.required.length()").value(5))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value(
                        org.hamcrest.Matchers.containsString("Based on partial data: 5 of 6 expected readings.")))
                .andRespond(withSuccess(geminiResponse(GOOD_ANSWER), MediaType.APPLICATION_JSON));

        AiExplanation ai = client.explain(context);

        server.verify();
        assertThat(ai.assessment()).isEqualTo(Assessment.GENUINE_TREND);
        assertThat(ai.likelyCause()).isEqualTo("Consistent with regional haze");
        assertThat(ai.evidence()).hasSize(2);
        assertThat(ai.assumptions()).containsExactly("Station is reporting correctly");
    }

    @Test
    void keyIsNeverPutInTheUrl() {
        server.expect(requestTo(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-key"))))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.explain(context)).isInstanceOf(ExplanationException.class);
        server.verify();
    }

    @Test
    void missingKey_failsWithoutCallingTheApi() {
        GeminiExplanationClient unconfigured = GeminiExplanationClient.withRestClient(props(true, " "),
                RestClient.builder().build(), mapper);

        assertThatThrownBy(() -> unconfigured.explain(context))
                .isInstanceOf(ExplanationException.class)
                .hasMessageContaining("GEMINI_API_KEY is not set");
    }

    @Test
    void rateLimited_throwsExplanationException() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.explain(context))
                .isInstanceOf(ExplanationException.class)
                .hasMessageContaining("Gemini call failed");
    }

    @Test
    void unknownAssessment_isRejected() throws Exception {
        String bad = GOOD_ANSWER.replace("GENUINE_TREND", "PANIC");
        assertThatThrownBy(() -> client.parse(geminiResponse(bad)))
                .isInstanceOf(ExplanationException.class)
                .hasMessageContaining("unknown assessment");
    }

    @Test
    void missingField_isRejected() throws Exception {
        String bad = """
                {"assessment":"UNCERTAIN","likelyCause":"","explanation":"x","evidence":["a"],"assumptions":["b"]}
                """;
        assertThatThrownBy(() -> client.parse(geminiResponse(bad)))
                .isInstanceOf(ExplanationException.class)
                .hasMessageContaining("likelyCause");
    }

    @Test
    void answerThatIsNotJson_isRejected() throws Exception {
        assertThatThrownBy(() -> client.parse(geminiResponse("Sure! Here is my analysis...")))
                .isInstanceOf(ExplanationException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void blockedPrompt_isReportedWithTheReason() {
        assertThatThrownBy(() -> client.parse("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"))
                .isInstanceOf(ExplanationException.class)
                .hasMessageContaining("SAFETY");
    }
}
