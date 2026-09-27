package com.cs203.healthwatch.ingestion;

import com.cs203.healthwatch.ingestion.config.IngestionHttpProperties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

/**
 * Unit tests for FetchClient's retry/backoff behaviour.
 *
 * Deliberately NOT using Mockito to mock RestClient's fluent chain (get().uri().retrieve().body()) —
 * the self-typed generics on RequestHeadersUriSpec/RequestHeadersSpec make that mocking fragile and
 * hard to read. Instead we bind a real RestClient to MockRestServiceServer (already available via
 * spring-boot-starter-test, no new dependency needed) and script the sequence of responses/failures
 * the fake server returns for each successive call. This also lets us simulate transport-level
 * failures (IOException) which a mocked chain can't produce realistically.
 */
class FetchClientTest {

    private static final String URL = "https://example.com/data";

    private MockRestServiceServer server;
    private FetchClient fetchClient;

    private void setUp(IngestionHttpProperties props) {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        this.fetchClient = new FetchClient(restClient, props);
    }

    /** Keep backoff sleeps at 1ms so retry tests run fast. */
    private static IngestionHttpProperties fastRetryProps(int maxAttempts) {
        return new IngestionHttpProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(10), maxAttempts, Duration.ofMillis(1));
    }

    @AfterEach
    void clearAnyLeakedInterruptFlag() {
        Thread.interrupted();
    }

    // ---------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------

    @Test
    void returnsBodyOnFirstSuccessfulAttempt() {
        setUp(fastRetryProps(3));
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        String result = fetchClient.fetch(URL);

        assertThat(result).isEqualTo("{\"ok\":true}");
        server.verify(); // exactly one request made
    }

    // ---------------------------------------------------------------
    // Retryable: 429 Too Many Requests
    // ---------------------------------------------------------------

    @Test
    void retriesOnTooManyRequestsThenSucceeds() {
        setUp(fastRetryProps(3));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withSuccess("recovered", MediaType.TEXT_PLAIN));

        String result = fetchClient.fetch(URL);

        assertThat(result).isEqualTo("recovered");
        server.verify(); // both expected calls happened, in order
    }

    @Test
    void givesUpAfterMaxAttemptsOnPersistentRateLimiting() {
        setUp(fastRetryProps(3));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        String result = fetchClient.fetch(URL);

        assertThat(result).isNull();
        server.verify(); // exactly 3 attempts — a 4th expectation here would fail verify()
    }

    // ---------------------------------------------------------------
    // Non-retryable: other 4xx client errors (e.g. 404, 401)
    // ---------------------------------------------------------------

    @Test
    void nonRetryableClientErrorStopsAfterOneAttempt() {
        setUp(fastRetryProps(3));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        // deliberately no second .expect(): if FetchClient wrongly retried past a plain
        // 404, the extra request would have no matching expectation and server.verify()
        // (or the request itself) would fail.

        String result = fetchClient.fetch(URL);

        assertThat(result).isNull();
        server.verify();
    }

    // ---------------------------------------------------------------
    // Retryable: generic transport failure (connection reset, timeout, etc.)
    // ---------------------------------------------------------------

    @Test
    void retriesOnConnectionFailureThenSucceeds() {
        setUp(fastRetryProps(3));
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new IOException("connection reset");
        });
        server.expect(requestTo(URL)).andRespond(withSuccess("recovered", MediaType.TEXT_PLAIN));

        String result = fetchClient.fetch(URL);

        assertThat(result).isEqualTo("recovered");
        server.verify();
    }

    @Test
    void retriesOnServerErrorThenSucceeds() {
        setUp(fastRetryProps(3));
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withSuccess("recovered", MediaType.TEXT_PLAIN));

        String result = fetchClient.fetch(URL);

        assertThat(result).isEqualTo("recovered");
        server.verify();
    }

    @Test
    void givesUpAfterMaxAttemptsOnPersistentConnectionFailure() {
        setUp(fastRetryProps(3));
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(URL)).andRespond(request -> {
                throw new IOException("down");
            });
        }

        String result = fetchClient.fetch(URL);

        assertThat(result).isNull();
        server.verify();
    }

    @Test
    void givesUpAfterMaxAttemptsOnPersistentServerError() {
        setUp(fastRetryProps(3));
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        String result = fetchClient.fetch(URL);

        assertThat(result).isNull();
        server.verify();
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"BAD_REQUEST", "UNAUTHORIZED", "FORBIDDEN"})
    void otherNonRetryableClientErrorsStopAfterOneAttempt(HttpStatus status) {
        setUp(fastRetryProps(3));
        server.expect(requestTo(URL)).andRespond(withStatus(status));

        String result = fetchClient.fetch(URL);

        assertThat(result).isNull();
        server.verify();
    }

    // ---------------------------------------------------------------
    // Backoff grows exponentially between attempts
    // ---------------------------------------------------------------

    @Test
    void backoffDoublesBetweenAttempts() {
        // initial 50ms, 3 attempts -> waits of 50ms then 100ms, none after the last
        setUp(new IngestionHttpProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(10), 3, Duration.ofMillis(50)));
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(URL)).andRespond(withServerError());
        }

        long start = System.nanoTime();
        fetchClient.fetch(URL);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // lower bound only (sleep never returns early); linear backoff would give 100ms
        assertThat(elapsedMs).isGreaterThanOrEqualTo(150);
        server.verify();
    }

    // ---------------------------------------------------------------
    // Config edge case: maxAttempts = 1 means no retry at all
    // ---------------------------------------------------------------

    @Test
    void maxAttemptsOfOneMeansNoRetryAtAll() {
        setUp(fastRetryProps(1));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        // a second expectation would fail verify() if FetchClient retried past maxAttempts=1

        String result = fetchClient.fetch(URL);

        assertThat(result).isNull();
        server.verify();
    }

    // ---------------------------------------------------------------
    // Thread interruption during backoff sleep must abandon retries cleanly
    // ---------------------------------------------------------------

    @Test
    void interruptedDuringBackoffStopsRetryingAndReturnsNull() throws InterruptedException {
        // Long backoff so we have a real window to interrupt the thread mid-sleep.
        IngestionHttpProperties slowBackoffProps = new IngestionHttpProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(10), 3, Duration.ofSeconds(30));
        setUp(slowBackoffProps);
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        // only one expectation: fetch() must not attempt a second call once interrupted

        AtomicReference<String> resultRef = new AtomicReference<>();
        Thread worker = new Thread(() -> resultRef.set(fetchClient.fetch(URL)));
        worker.start();
        Thread.sleep(200); // let the worker enter Thread.sleep() inside backoff()
        worker.interrupt();
        worker.join(2000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(resultRef.get()).isNull();
        server.verify();
    }
}
