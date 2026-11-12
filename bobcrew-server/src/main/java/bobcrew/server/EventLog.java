package bobcrew.server;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The numbered events of one session: the last {@code capacity} are kept, so a client that
 * connects late or reconnects can ask for everything after the last event it saw.
 *
 * <p>Each subscriber has its own queue and reads it on its own thread, so a slow client never
 * delays the agent or other clients. A subscriber that falls too far behind is dropped; it
 * reconnects and resumes from its last event.
 */
final class EventLog {

    /** How many undelivered events one subscriber may accumulate before it is dropped. */
    private static final int MAX_BACKLOG = 20_000;

    /** Queued after a subscriber's last event, so that a waiting reader notices the end at once. */
    private static final UiEvent END = new UiEvent(-1, Instant.EPOCH, "end", null, null);

    private final int capacity;

    private final ArrayDeque<UiEvent> events = new ArrayDeque<>();

    private final List<Subscription> subscriptions = new ArrayList<>();

    private long lastSeq;

    private boolean closed;

    EventLog(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Records an event and hands it to the subscribers. Ignored once the log is closed. */
    synchronized UiEvent append(String type, String user, Object data) {
        if (closed) {
            return null;
        }
        UiEvent event = new UiEvent(++lastSeq, Instant.now(), type, user, data);
        events.addLast(event);
        if (events.size() > capacity) {
            events.removeFirst();
        }
        subscriptions.removeIf(subscription -> !subscription.offer(event));
        return event;
    }

    synchronized long lastSeq() {
        return lastSeq;
    }

    /**
     * Subscribes to the events after {@code afterSeq}: the kept ones first, then new ones as they
     * happen, with none missed or repeated in between.
     */
    synchronized Subscription subscribe(long afterSeq) {
        Subscription subscription = new Subscription();
        for (UiEvent event : events) {
            if (event.seq() > afterSeq) {
                subscription.offer(event);
            }
        }
        if (closed) {
            subscription.end();
        } else {
            subscriptions.add(subscription);
        }
        return subscription;
    }

    /** Ends the log: subscribers receive what was already recorded, then their stream ends. */
    synchronized void close() {
        closed = true;
        subscriptions.forEach(Subscription::end);
        subscriptions.clear();
    }

    private synchronized void remove(Subscription subscription) {
        subscriptions.remove(subscription);
    }

    /** One client's view of the log. */
    final class Subscription implements AutoCloseable {

        private final BlockingQueue<UiEvent> queue = new LinkedBlockingQueue<>();

        private volatile boolean ended;

        private volatile boolean logClosed;

        private boolean offer(UiEvent event) {
            if (queue.size() >= MAX_BACKLOG) {
                ended = true;
                return false;
            }
            queue.add(event);
            return true;
        }

        private void end() {
            logClosed = true;
            ended = true;
            // wakes a reader that is waiting in poll
            queue.add(END);
        }

        /** True if the stream ended because the session did, rather than because this subscriber was dropped. */
        boolean isLogClosed() {
            return logClosed;
        }

        /** Waits for the next event; returns null if none arrived in time or the stream ended. */
        UiEvent poll(Duration timeout) throws InterruptedException {
            UiEvent event = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return event == END ? null : event;
        }

        /** True once no more events will come and all of them were read. */
        boolean isFinished() {
            return ended && queue.isEmpty();
        }

        @Override
        public void close() {
            ended = true;
            remove(this);
        }
    }
}
