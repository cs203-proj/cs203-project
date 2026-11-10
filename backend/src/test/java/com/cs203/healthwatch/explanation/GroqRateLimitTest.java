package com.cs203.healthwatch.explanation;

import static com.cs203.healthwatch.explanation.TestExplanations.context;
import static com.cs203.healthwatch.explanation.TestExplanations.hourly;
import static com.cs203.healthwatch.explanation.TestExplanations.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** CG-152: a 429 from Groq is reported as RATE_LIMITED, with Retry-After when the provider sends one. */
class GroqRateLimitTest {

    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";

    private MockRestServiceServer server;
    private GroqExplanationClient client;
    private ExplanationContext context;

    @BeforeEach
    void setUp() {
        ExplanationProperties props = props(true, "test-key");
        RestClient.Builder builder = RestClient.builder().baseUrl(props.baseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        client = GroqExplanationClient.withRestClient(props, builder.build(), new ObjectMapper());
        context = context(false, new DataQuality(true, "Based on complete data.", 6, 6),
                hourly(Instant.parse("2026-10-18T03:00:00Z"), 18, 20, 25, 45, 55, 70));
    }

    @Test
    void http429_isStoredWithTheRateLimitedPrefix_andReadsRetryAfter() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "30"));

        RateLimitedException e = catchThrowableOfType(() -> client.explain(context), RateLimitedException.class);

        assertThat(e).isNotNull();
        assertThat(e.getMessage()).startsWith("RATE_LIMITED:").contains("Groq call failed");
        assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void http429_withoutRetryAfter_hasNoRetryAfter() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        RateLimitedException e = catchThrowableOfType(() -> client.explain(context), RateLimitedException.class);

        assertThat(e.getMessage()).startsWith("RATE_LIMITED:");
        assertThat(e.retryAfter()).isNull();
    }

    @Test
    void http429_withAnHttpDateRetryAfter_isTreatedAsAbsent() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT"));

        RateLimitedException e = catchThrowableOfType(() -> client.explain(context), RateLimitedException.class);

        assertThat(e.retryAfter()).isNull();
    }

    @Test
    void otherHttpErrors_arePlainFailures_notRateLimits() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        ExplanationException e = catchThrowableOfType(() -> client.explain(context), ExplanationException.class);

        assertThat(e).isNotInstanceOf(RateLimitedException.class);
        assertThat(e.getMessage()).doesNotStartWith("RATE_LIMITED:");
    }
}
