package io.github.danieloh30.maop.console.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A planned scale-out reads "Scaling up"; pods that crash, fail to pull or are old and unready stay Degraded. */
class ScalingStatusTest {

    static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    static Pod pod(boolean ready, int ageSeconds, int restarts, String waitingReason) {
        return new PodBuilder()
                .withNewMetadata().withName("p").withCreationTimestamp(NOW.minusSeconds(ageSeconds).toString()).endMetadata()
                .withNewStatus()
                .addNewCondition().withType("Ready").withStatus(ready ? "True" : "False").endCondition()
                .addNewContainerStatus().withName("app").withRestartCount(restarts)
                .withNewState().withNewWaiting().withReason(waitingReason).endWaiting().endState()
                .endContainerStatus()
                .endStatus().build();
    }

    @Test
    void newPodsStartingAreAScaleUp() {
        List<Pod> pods = List.of(pod(true, 3600, 0, null), pod(false, 20, 0, "ContainerCreating"), pod(false, 15, 0, null));
        assertTrue(SnapshotService.isScalingUp(3, 1, pods, NOW));
    }

    @Test
    void failingOrOldPodsAreNotAScaleUp() {
        assertFalse(SnapshotService.isScalingUp(2, 1, List.of(pod(true, 3600, 0, null), pod(false, 20, 0, "ImagePullBackOff")), NOW));
        assertFalse(SnapshotService.isScalingUp(2, 1, List.of(pod(true, 3600, 0, null), pod(false, 60, 2, null)), NOW));
        assertFalse(SnapshotService.isScalingUp(2, 1, List.of(pod(true, 3600, 0, null), pod(false, 900, 0, null)), NOW));
    }

    @Test
    void notEnoughStartingPodsOrNoneReadyIsNotAScaleUp() {
        assertFalse(SnapshotService.isScalingUp(4, 1, List.of(pod(true, 3600, 0, null), pod(false, 20, 0, null)), NOW),
                "a replica with no pod at all is a shortfall");
        assertFalse(SnapshotService.isScalingUp(2, 0, List.of(pod(false, 20, 0, null), pod(false, 20, 0, null)), NOW));
        assertFalse(SnapshotService.isScalingUp(1, 1, List.of(pod(true, 3600, 0, null)), NOW));
    }
}
