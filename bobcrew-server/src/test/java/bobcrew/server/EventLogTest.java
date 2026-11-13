package bobcrew.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class EventLogTest {

    private static final Duration SHORT = Duration.ofMillis(50);

    @Test
    void replaysKeptEventsThenDeliversNewOnes() throws Exception {
        EventLog log = new EventLog(100);
        log.append("a", null, null);
        log.append("b", null, null);
        log.append("c", null, null);
        try (EventLog.Subscription subscription = log.subscribe(1)) {
            assertThat(drain(subscription)).containsExactly(2L, 3L);
            log.append("d", null, null);
            assertThat(drain(subscription)).containsExactly(4L);
            assertThat(subscription.isFinished()).isFalse();
        }
    }

    @Test
    void keepsOnlyTheLastEvents() throws Exception {
        EventLog log = new EventLog(3);
        for (int i = 0; i < 10; i++) {
            log.append("e", null, null);
        }
        assertThat(log.lastSeq()).isEqualTo(10);
        try (EventLog.Subscription subscription = log.subscribe(0)) {
            assertThat(drain(subscription)).containsExactly(8L, 9L, 10L);
        }
    }

    @Test
    void discardForgetsKeptEventsButNumberingGoesOn() throws Exception {
        EventLog log = new EventLog(10);
        log.append("a", null, null);
        EventLog.Subscription before = log.subscribe(0);
        log.append("b", null, null);
        log.discard();
        log.append("c", null, null);
        // what was already queued is still delivered, in order
        assertThat(drain(before)).containsExactly(1L, 2L, 3L);
        // a new or reconnecting client only gets what was recorded after the discard
        try (EventLog.Subscription fresh = log.subscribe(0); EventLog.Subscription old = log.subscribe(1)) {
            assertThat(drain(fresh)).containsExactly(3L);
            assertThat(drain(old)).containsExactly(3L);
        }
        assertThat(log.lastSeq()).isEqualTo(3);
    }

    @Test
    void closingEndsSubscriptionsAfterTheirLastEvent() throws Exception {
        EventLog log = new EventLog(10);
        log.append("a", null, null);
        EventLog.Subscription subscription = log.subscribe(0);
        log.close();
        assertThat(subscription.isFinished()).isFalse();
        assertThat(drain(subscription)).containsExactly(1L);
        assertThat(subscription.isFinished()).isTrue();
        assertThat(subscription.isLogClosed()).isTrue();
        assertThat(log.append("late", null, null)).isNull();

        EventLog.Subscription late = log.subscribe(0);
        assertThat(drain(late)).containsExactly(1L);
        assertThat(late.isFinished()).isTrue();
    }

    @Test
    void closingWakesAWaitingReader() throws Exception {
        EventLog log = new EventLog(10);
        EventLog.Subscription subscription = log.subscribe(0);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
            log.close();
        });
        long start = System.nanoTime();
        assertThat(subscription.poll(Duration.ofSeconds(20))).isNull();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(subscription.isFinished()).isTrue();
    }

    private static List<Long> drain(EventLog.Subscription subscription) throws InterruptedException {
        List<Long> sequences = new ArrayList<>();
        UiEvent event;
        while ((event = subscription.poll(SHORT)) != null) {
            sequences.add(event.seq());
        }
        return sequences;
    }
}
