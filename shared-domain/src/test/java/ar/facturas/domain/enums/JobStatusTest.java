package ar.facturas.domain.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static ar.facturas.domain.enums.JobStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobStatusTest {

    @Test
    void onlyPendingIsProcessable() {
        assertThat(PENDING.isProcessable()).isTrue();
    }

    /**
     * An allowlist on purpose. If someone adds a new state and does not
     * explicitly decide it is processable, this test keeps it out instead
     * of letting the worker silently start claiming it.
     */
    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void noOtherStateIsProcessable(JobStatus status) {
        assertThat(status.isProcessable()).isFalse();
    }

    @Test
    void theHappyPath() {
        assertThat(PENDING.canTransitionTo(PROCESSING)).isTrue();
        assertThat(PROCESSING.canTransitionTo(EXTRACTED)).isTrue();
        assertThat(EXTRACTED.canTransitionTo(CONFIRMED)).isTrue();
    }

    @Test
    void everyReprocessPathGoesThroughPending() {
        assertThat(FAILED.canTransitionTo(PENDING)).isTrue();        // DLQ retry
        assertThat(DEAD_LETTER.canTransitionTo(PENDING)).isTrue();   // manual retry
        assertThat(PROCESSING.canTransitionTo(PENDING)).isTrue();    // reaper for stuck jobs
    }

    @Test
    void aFailedJobIsNeverProcessedDirectly() {
        // If it were, a stale copy of the original message would be
        // indistinguishable from the deliberate retry and both would run.
        assertThat(FAILED.isProcessable()).isFalse();
        assertThat(FAILED.canTransitionTo(PROCESSING)).isFalse();
    }

    @Test
    void aDuplicateIsDetectedWhileProcessingAndGoesNowhereAfter() {
        assertThat(PROCESSING.canTransitionTo(DUPLICATE)).isTrue();
        assertThat(DUPLICATE.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"CONFIRMED", "DUPLICATE"})
    void terminalStatesHaveNoWayOut(JobStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (JobStatus target : values()) {
            assertThat(terminal.canTransitionTo(target))
                    .as("%s should not be able to reach %s", terminal, target)
                    .isFalse();
        }
    }

    @Test
    void noStateTransitionsToItself() {
        for (JobStatus status : values()) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }
    }

    @Test
    void requiringAnInvalidTransitionNamesBothStates() {
        assertThatThrownBy(() -> CONFIRMED.requireTransitionTo(PROCESSING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFIRMED")
                .hasMessageContaining("PROCESSING");
    }

    @Test
    void requiringAValidTransitionReturnsTheTarget() {
        assertThat(PENDING.requireTransitionTo(PROCESSING)).isEqualTo(PROCESSING);
    }
}
