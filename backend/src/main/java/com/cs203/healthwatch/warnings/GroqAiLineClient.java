package com.cs203.healthwatch.warnings;

import com.cs203.healthwatch.explanation.ExplanationProperties;
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
 * Groq through its OpenAI-compatible chat completions endpoint, the same account and model as AI explanations
 * (CG-135). Strict json_schema output forces a single "whyItMatters" field, validated here like CG-135. The key is
 * sent as a Bearer header, never in the URL.
 */
@Component
public class GroqAiLineClient implements AiLineClient {

    static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("whyItMatters", Map.of("type", "string")),
            "required", List.of("whyItMatters"),
            "additionalProperties", false);

    private final ExplanationProperties props;
    private final RestClient http;
    private final ObjectMapper mapper;

    @Autowired
    public GroqAiLineClient(ExplanationProperties props, RestClient.Builder builder, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.timeout());
        factory.setReadTimeout(props.timeout());
        this.http = builder.baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    private GroqAiLineClient(RestClient http, ExplanationProperties props, ObjectMapper mapper) {
        this.props = props;
        this.http = http;
        this.mapper = mapper;
    }

    /** For tests: uses a RestClient bound to a mock server instead of the real one. */
    static GroqAiLineClient withRestClient(ExplanationProperties props, RestClient http, ObjectMapper mapper) {
        return new GroqAiLineClient(http, props, mapper);
    }

    @Override
    public boolean isConfigured() {
        return props.hasApiKey();
    }

    @Override
    public String write(AiLineContext context) {
        if (!props.hasApiKey()) {
            throw new IllegalStateException("AI provider not configured: GROQ_API_KEY is not set");
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
            throw new IllegalStateException("Groq call failed: " + e.getMessage(), e);
        }
        return parse(body);
    }

    static Map<String, Object> requestBody(String model, AiLineContext context) {
        return Map.of(
                "model", model,
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", AiLinePrompt.SYSTEM_INSTRUCTION),
                        Map.of("role", "user", "content", AiLinePrompt.facts(context))),
                "response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", Map.of("name", "warning_ai_line", "strict", true,
                                "schema", RESPONSE_SCHEMA)));
    }

    String parse(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody == null ? "" : responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new IllegalStateException("Groq returned no answer");
            }
            String line = mapper.readTree(content.asText()).path("whyItMatters").asText("");
            if (line.isBlank()) {
                throw new IllegalStateException("Groq reply is missing 'whyItMatters'");
            }
            return line;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Groq reply was not valid JSON: " + e.getOriginalMessage(), e);
        }
    }
}
