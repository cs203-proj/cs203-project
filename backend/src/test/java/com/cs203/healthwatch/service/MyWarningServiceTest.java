package com.cs203.healthwatch.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.cs203.healthwatch.confidence.ConfidenceLevel;
import com.cs203.healthwatch.dto.MyWarningListResponse;
import com.cs203.healthwatch.dto.MyWarningResponse;
import com.cs203.healthwatch.events.DetectedEvent;
import com.cs203.healthwatch.events.EventRepository;
import com.cs203.healthwatch.events.EventStatus;
import com.cs203.healthwatch.events.TestEvents;
import com.cs203.healthwatch.explanation.EventExplanation;
import com.cs203.healthwatch.explanation.EventExplanationRepository;
import com.cs203.healthwatch.explanation.ExplanationStatus;
import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import com.cs203.healthwatch.warnings.TestWarnings;
import com.cs203.healthwatch.warnings.Warning;
import com.cs203.healthwatch.warnings.WarningFeedProperties;
import com.cs203.healthwatch.warnings.WarningRepository;
import com.cs203.healthwatch.warnings.WarningSeverity;
import com.cs203.healthwatch.warnings.WarningStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/** CG-22 (the feed) and CG-23 (confidence, "why this applies", data-quality note) against the in-memory database. */
@DataJpaTest
@ActiveProfiles("test")
class MyWarningServiceTest {

    static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    @Autowired TestEntityManager em;
    @Autowired UserRepository userRepository;
    @Autowired WarningRepository warningRepository;
    @Autowired EventRepository eventRepository;
    @Autowired EventExplanationRepository explanationRepository;

    private DetectedEvent eastEvent;
    private DetectedEvent centralEvent;

    @BeforeEach
    void events() {
        eastEvent = event("east");
        centralEvent = event("central");
    }

    private MyWarningService service() {
        return service(Duration.ofHours(24));
    }

    private MyWarningService service(Duration window) {
        return new MyWarningService(userRepository, warningRepository, eventRepository, explanationRepository,
                new WarningFeedProperties(window), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DetectedEvent event(String region) {
        DetectedEvent e = TestEvents.event(6.0, EventStatus.CONFIRMED, "2026-10-14T01:00:00Z");
        e.setRegion(region);
        e.setConfidenceLevel(ConfidenceLevel.MEDIUM);
        return em.persistAndFlush(e);
    }

    private User user(String name, String region, String audience, String... conditions) {
        User u = new User(name, "hash", "viewer");
        u.setRegion(region);
        u.setAudience(audience);
        u.setHealthConditions(new HashSet<>(Arrays.asList(conditions)));
        return em.persistAndFlush(u);
    }

    private Warning warning(DetectedEvent event, String audience, WarningSeverity severity, WarningStatus status,
                            Instant approvedAt) {
        Warning w = TestWarnings.warning(event.getId(), severity, status);
        w.setAudience(audience);
        w.setApprovedAt(approvedAt);
        return em.persistAndFlush(w);
    }

    private Warning approved(DetectedEvent event, String audience, WarningSeverity severity, Instant approvedAt) {
        return warning(event, audience, severity, WarningStatus.APPROVED, approvedAt);
    }

    private static final Instant RECENT = NOW.minus(Duration.ofHours(1));

    // ---- CG-22 ----

    @Test
    void usersWithDifferentRegionAndAudienceSeeDifferentWarningsForTheSameEvents() {
        Warning eastGeneral = approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, RECENT);
        Warning eastVulnerable = approved(eastEvent, "VULNERABLE", WarningSeverity.HIGH, RECENT);
        Warning centralGeneral = approved(centralEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, RECENT);
        Warning centralVulnerable = approved(centralEvent, "VULNERABLE", WarningSeverity.HIGH, RECENT);
        User eastVuln = user("east-vuln", "east", "VULNERABLE", "ASTHMA");
        User centralGen = user("central-gen", "central", "GENERAL_PUBLIC");
        User centralVuln = user("central-vuln", "central", "VULNERABLE");

        assertThat(ids(service().forUser(eastVuln.getId()))).containsExactly(eastVulnerable.getId());
        assertThat(ids(service().forUser(centralGen.getId()))).containsExactly(centralGeneral.getId());
        assertThat(ids(service().forUser(centralVuln.getId()))).containsExactly(centralVulnerable.getId());
        assertThat(eastGeneral.getId()).isNotIn(ids(service().forUser(eastVuln.getId())));
    }

    @Test
    void neverReturnsPendingOrSuppressedWarnings() {
        Warning ok = approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.LOW, RECENT);
        warning(eastEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, WarningStatus.PENDING_REVIEW, null);
        warning(eastEvent, "GENERAL_PUBLIC", WarningSeverity.CRITICAL, WarningStatus.SUPPRESSED, RECENT);
        // downgraded, then suppressed: severity was lowered before the suppress, status is what counts
        warning(eastEvent, "GENERAL_PUBLIC", WarningSeverity.LOW, WarningStatus.SUPPRESSED, RECENT);
        User u = user("u", "east", "GENERAL_PUBLIC");

        assertThat(ids(service().forUser(u.getId()))).containsExactly(ok.getId());
    }

    @Test
    void returnsTheAdminsEditedTextInsteadOfTheOriginalDraft() {
        Warning edited = approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, RECENT);
        edited.setEditedText("Edited by admin: stay indoors.");
        em.persistAndFlush(edited);
        Warning untouched = approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.LOW, RECENT);
        User u = user("u", "east", "GENERAL_PUBLIC");

        List<MyWarningResponse> items = service().forUser(u.getId()).items();

        assertThat(items).extracting(MyWarningResponse::id).containsExactly(edited.getId(), untouched.getId());
        assertThat(items.get(0).text()).isEqualTo("Edited by admin: stay indoors.")
                .isNotEqualTo(edited.getWarningText());
        assertThat(items.get(1).text()).isEqualTo(untouched.getWarningText());
    }

    @Test
    void blankEditFallsBackToTheOriginalDraft() {
        Warning w = approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, RECENT);
        w.setEditedText("   ");
        em.persistAndFlush(w);
        User u = user("u", "east", "GENERAL_PUBLIC");

        assertThat(service().forUser(u.getId()).items().get(0).text()).isEqualTo(w.getWarningText());
    }

    @Test
    void onlyWarningsApprovedInsideTheWindowAreActive() {
        Warning inside = approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, NOW.minus(Duration.ofHours(23)));
        approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.CRITICAL, NOW.minus(Duration.ofHours(25)));
        User u = user("u", "east", "GENERAL_PUBLIC");

        assertThat(ids(service().forUser(u.getId()))).containsExactly(inside.getId());
    }

    @Test
    void windowIsConfigurable() {
        approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, NOW.minus(Duration.ofHours(2)));
        User u = user("u", "east", "GENERAL_PUBLIC");

        assertThat(service(Duration.ofHours(1)).forUser(u.getId()).items()).isEmpty();
        assertThat(service(Duration.ofHours(3)).forUser(u.getId()).items()).hasSize(1);
    }

    @Test
    void sortedBySeverityThenNewestFirst() {
        Warning lowNew = approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.LOW, NOW.minus(Duration.ofMinutes(5)));
        Warning critOld = approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.CRITICAL, NOW.minus(Duration.ofHours(5)));
        Warning highOld = approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, NOW.minus(Duration.ofHours(4)));
        Warning highNew = approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, NOW.minus(Duration.ofHours(1)));
        Warning medium = approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.MEDIUM, NOW.minus(Duration.ofHours(2)));
        User u = user("u", "east", "GENERAL_PUBLIC");

        assertThat(ids(service().forUser(u.getId())))
                .containsExactly(critOld.getId(), highNew.getId(), highOld.getId(), medium.getId(), lowNew.getId());
    }

    @Test
    void userWithoutRegionOrAudienceGetsEmptyListAndMessage() {
        approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, RECENT);
        User noRegion = user("no-region", null, "GENERAL_PUBLIC");
        User noAudience = user("no-audience", "east", null);
        User nothing = user("nothing", null, null);

        for (User u : List.of(noRegion, noAudience, nothing)) {
            MyWarningListResponse r = service().forUser(u.getId());
            assertThat(r.items()).isEmpty();
            assertThat(r.message()).contains("Complete your profile");
        }
    }

    @Test
    void completeProfileWithNothingActiveGetsEmptyListAndNoMessage() {
        User u = user("u", "east", "GENERAL_PUBLIC");

        MyWarningListResponse r = service().forUser(u.getId());

        assertThat(r.items()).isEmpty();
        assertThat(r.message()).isNull();
    }

    // ---- CG-23 ----

    @Test
    void showsConfidenceLevelAndItsMeaning() {
        approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, RECENT);
        User u = user("u", "east", "GENERAL_PUBLIC");

        MyWarningResponse.Confidence c = service().forUser(u.getId()).items().get(0).confidence();

        assertThat(c.level()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(c.meaning()).isEqualTo(MyWarningService.meaning(ConfidenceLevel.MEDIUM)).isNotBlank();
    }

    @Test
    void eventNeverScoredSaysConfidenceNotAssessed() {
        DetectedEvent unscored = event("west");
        unscored.setConfidenceLevel(null);
        em.persistAndFlush(unscored);
        approved(unscored, "GENERAL_PUBLIC", WarningSeverity.HIGH, RECENT);
        User u = user("u", "west", "GENERAL_PUBLIC");

        MyWarningResponse.Confidence c = service().forUser(u.getId()).items().get(0).confidence();

        assertThat(c.level()).isNull();
        assertThat(c.meaning()).contains("not been assessed");
    }

    @Test
    void whyThisAppliesIsBuiltFromTheUsersProfile() {
        approved(eastEvent, "VULNERABLE", WarningSeverity.HIGH, RECENT);
        User u = user("u", "east", "VULNERABLE");

        assertThat(service().forUser(u.getId()).items().get(0).whyThisApplies())
                .isEqualTo("You are in the east region and receive advice for vulnerable groups.");
    }

    @Test
    void whyLineNamesOnlyTheConditionsThisUserEntered() {
        approved(eastEvent, "VULNERABLE", WarningSeverity.HIGH, RECENT);
        User u = user("u", "east", "VULNERABLE", "ASTHMA", "HEART_DISEASE");

        String why = service().forUser(u.getId()).items().get(0).whyThisApplies();

        assertThat(why).contains("asthma", "heart disease").doesNotContain("copd", "diabetes", "pregnancy");
    }

    @Test
    void generalPublicUserWithConditionsGetsNoConditionsInTheLine() {
        approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, RECENT);
        User u = user("u", "east", "GENERAL_PUBLIC", "ASTHMA");

        assertThat(service().forUser(u.getId()).items().get(0).whyThisApplies())
                .isEqualTo("You are in the east region and receive advice for the general public.");
    }

    @Test
    void aiLineIsShownSeparatelyAndLabelled() {
        Warning w = approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, RECENT);
        w.setAiLine("Keep your windows closed today.");
        em.persistAndFlush(w);
        approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.LOW, RECENT);
        User u = user("u", "east", "GENERAL_PUBLIC");

        List<MyWarningResponse> items = service().forUser(u.getId()).items();

        assertThat(items.get(0).aiLine().text()).isEqualTo("Keep your windows closed today.");
        assertThat(items.get(0).aiLine().label()).isEqualTo("AI-generated");
        assertThat(items.get(0).whyThisApplies()).doesNotContain("windows");
        assertThat(items.get(1).aiLine()).isNull();
    }

    @Test
    void staleOrPartialDataNoteIsShown() {
        approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, RECENT);
        explain(eastEvent, false, "Based on partial data: 4 of 6 expected readings in the last 6 hours.");
        User u = user("u", "east", "GENERAL_PUBLIC");

        assertThat(service().forUser(u.getId()).items().get(0).dataQualityNote())
                .isEqualTo("Based on partial data: 4 of 6 expected readings in the last 6 hours.");
    }

    @Test
    void noNoteWhenDataWasCompleteOrThereIsNoExplanation() {
        approved(eastEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, RECENT);
        approved(centralEvent, "GENERAL_PUBLIC", WarningSeverity.HIGH, RECENT);
        explain(eastEvent, true, "Based on complete data: 6 of 6 expected readings in the last 6 hours.");
        User eastUser = user("e", "east", "GENERAL_PUBLIC");
        User centralUser = user("c", "central", "GENERAL_PUBLIC");

        assertThat(service().forUser(eastUser.getId()).items().get(0).dataQualityNote()).isNull();
        assertThat(service().forUser(centralUser.getId()).items().get(0).dataQualityNote()).isNull();
    }

    private void explain(DetectedEvent event, boolean complete, String note) {
        EventExplanation x = new EventExplanation();
        x.setEventId(event.getId());
        x.setStatus(ExplanationStatus.OK);
        x.setDataComplete(complete);
        x.setDataQualityNote(note);
        em.persistAndFlush(x);
    }

    private static List<java.util.UUID> ids(MyWarningListResponse r) {
        return r.items().stream().map(MyWarningResponse::id).toList();
    }
}
