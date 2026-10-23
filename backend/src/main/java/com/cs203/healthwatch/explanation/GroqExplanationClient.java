package com.cs203.healthwatch.explanation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Groq (free plan) through its OpenAI-compatible chat completions endpoint. Strict json_schema output forces the
 * reply into the same five fields every time; the reply is still validated here. The API key is sent as a Bearer
 * header, never in the URL, so it cannot end up in access logs.
 */
@Component
public class GroqExplanationClient implements ExplanationClient {

    private final ExplanationProperties props;
    private final RestClient http;
    private final ObjectMapper mapper;

    @Autowired
    public GroqExplanationClient(ExplanationProperties props, RestClient.Builder builder, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = builder.baseUrl(props.baseUrl()).requestFactory(timeouts(props)).build();
    }

    private GroqExplanationClient(RestClient http, ExplanationProperties props, ObjectMapper mapper) {
        this.props = props;
        this.http = http;
        this.mapper = mapper;
    }

    /** For tests: uses a RestClient bound to a mock server instead of the real one. */
    static GroqExplanationClient withRestClient(ExplanationProperties props, RestClient http, ObjectMapper mapper) {
        return new GroqExplanationClient(http, props, mapper);
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
            throw new ExplanationException("AI provider not configured: GROQ_API_KEY is not set");
        }

        String body;
        try {
            body = http.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(props.model(), context))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // Message only: the key is in a header, so it is never part of the message.
            throw new ExplanationException("Groq call failed: " + e.getMessage(), e);
        }
        return parse(body);
    }

    static Map<String, Object> requestBody(String model, ExplanationContext context) {
        return Map.of(
                "model", model,
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", ExplanationPrompt.SYSTEM_INSTRUCTION),
                        Map.of("role", "user", "content", ExplanationPrompt.facts(context))),
                "response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", Map.of("name", "event_explanation", "strict", true,
                                "schema", RESPONSE_SCHEMA)));
    }

    private static final Map<String, Object> STRING = Map.of("type", "string");
    private static final Map<String, Object> STRING_LIST = Map.of("type", "array", "items", STRING);

    static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "assessment", Map.of("type", "string",
                            "enum", List.of("GENUINE_TREND", "LIKELY_NOISE", "UNCERTAIN")),
                    "likelyCause", STRING,
                    "explanation", STRING,
                    "evidence", STRING_LIST,
                    "assumptions", STRING_LIST),
            "required", List.of("assessment", "likelyCause", "explanation", "evidence", "assumptions"),
            "additionalProperties", false);

    /** Pulls the JSON answer out of a chat completions response and checks every field is usable. */
    AiExplanation parse(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody == null ? "" : responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                String reason = root.path("choices").path(0).path("finish_reason").asText("");
                if (reason.isEmpty()) {
                    reason = root.path("error").path("message").asText("no choices");
                }
                throw new ExplanationException("Groq returned no answer (" + reason + ")");
            }

            JsonNode answer = mapper.readTree(content.asText());
            Assessment assessment;
            try {
                assessment = Assessment.valueOf(answer.path("assessment").asText(""));
            } catch (IllegalArgumentException e) {
                throw new ExplanationException("Groq returned an unknown assessment: "
                        + answer.path("assessment").asText(""));
            }
            return new AiExplanation(
                    assessment,
                    requiredText(answer, "likelyCause"),
                    requiredText(answer, "explanation"),
                    stringList(answer, "evidence"),
                    stringList(answer, "assumptions"));
        } catch (JsonProcessingException e) {
            throw new ExplanationException("Groq reply was not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new ExplanationException("Groq reply is missing '" + field + "'");
        }
        return value;
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode array = node.path(field);
        if (!array.isArray() || array.isEmpty()) {
            throw new ExplanationException("Groq reply is missing '" + field + "'");
        }
        return StreamSupport.stream(array.spliterator(), false)
                .map(n -> n.asText("").trim())
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
