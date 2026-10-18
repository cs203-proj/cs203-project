package com.cs203.healthwatch.explanation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Google Gemini (free tier) via its REST generateContent endpoint, with structured JSON output so the reply always
 * has the same fields. The API key goes in the x-goog-api-key header, never in the URL, so it cannot end up in
 * access logs.
 */
@Component
public class GeminiExplanationClient implements ExplanationClient {

    private final ExplanationProperties props;
    private final RestClient http;
    private final ObjectMapper mapper;

    @Autowired
    public GeminiExplanationClient(ExplanationProperties props, RestClient.Builder builder, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = builder.baseUrl(props.baseUrl()).requestFactory(timeouts(props)).build();
    }

    private GeminiExplanationClient(RestClient http, ExplanationProperties props, ObjectMapper mapper) {
        this.props = props;
        this.http = http;
        this.mapper = mapper;
    }

    /** For tests: uses a RestClient bound to a mock server instead of the real one. */
    static GeminiExplanationClient withRestClient(ExplanationProperties props, RestClient http, ObjectMapper mapper) {
        return new GeminiExplanationClient(http, props, mapper);
    }

    private static SimpleClientHttpRequestFactory timeouts(ExplanationProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.timeout());
        factory.setReadTimeout(props.timeout());
        return factory;
    }

    @Override
    public String modelName() {
        return props.model();
    }

    @Override
    public AiExplanation explain(ExplanationContext context) {
        if (!props.hasApiKey()) {
            throw new ExplanationException("AI provider not configured: GEMINI_API_KEY is not set");
        }

        String body;
        try {
            body = http.post()
                    .uri("/models/{model}:generateContent", props.model())
                    .header("x-goog-api-key", props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(context))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // Message only: response bodies can be long, and the key is in a header so it is never in the message.
            throw new ExplanationException("Gemini call failed: " + e.getMessage(), e);
        }
        return parse(body);
    }

    static Map<String, Object> requestBody(ExplanationContext context) {
        return Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", ExplanationPrompt.SYSTEM_INSTRUCTION))),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", ExplanationPrompt.facts(context))))),
                "generationConfig", Map.of(
                        "temperature", 0.2,
                        "responseMimeType", "application/json",
                        "responseSchema", RESPONSE_SCHEMA));
    }

    private static final Map<String, Object> STRING = Map.of("type", "STRING");
    private static final Map<String, Object> STRING_LIST = Map.of("type", "ARRAY", "items", STRING);

    static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "assessment", Map.of("type", "STRING",
                            "enum", List.of("GENUINE_TREND", "LIKELY_NOISE", "UNCERTAIN")),
                    "likelyCause", STRING,
                    "explanation", STRING,
                    "evidence", STRING_LIST,
                    "assumptions", STRING_LIST),
            "required", List.of("assessment", "likelyCause", "explanation", "evidence", "assumptions"),
            "propertyOrdering", List.of("assessment", "likelyCause", "explanation", "evidence", "assumptions"));

    /** Pulls the JSON answer out of a generateContent response and checks every field is usable. */
    AiExplanation parse(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody == null ? "" : responseBody);
            JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (!text.isTextual() || text.asText().isBlank()) {
                String reason = root.path("candidates").path(0).path("finishReason").asText("");
                if (reason.isEmpty()) {
                    reason = root.path("promptFeedback").path("blockReason").asText("no candidates");
                }
                throw new ExplanationException("Gemini returned no answer (" + reason + ")");
            }

            JsonNode answer = mapper.readTree(text.asText());
            Assessment assessment;
            try {
                assessment = Assessment.valueOf(answer.path("assessment").asText(""));
            } catch (IllegalArgumentException e) {
                throw new ExplanationException("Gemini returned an unknown assessment: "
                        + answer.path("assessment").asText(""));
            }
            return new AiExplanation(
                    assessment,
                    requiredText(answer, "likelyCause"),
                    requiredText(answer, "explanation"),
                    stringList(answer, "evidence"),
                    stringList(answer, "assumptions"));
        } catch (JsonProcessingException e) {
            throw new ExplanationException("Gemini reply was not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new ExplanationException("Gemini reply is missing '" + field + "'");
        }
        return value;
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode array = node.path(field);
        if (!array.isArray() || array.isEmpty()) {
            throw new ExplanationException("Gemini reply is missing '" + field + "'");
        }
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(n -> n.asText("").trim())
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
