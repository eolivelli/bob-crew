package bobcrew.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * ACP over a container's standard streams: newline-delimited JSON-RPC, as the SDK's
 * {@code StdioAcpClientTransport} speaks it to a child process, but through a Docker attachment. The SDK's
 * transport interface is reactive, so this is the one class of ours that uses Reactor types.
 *
 * <p>{@link #connect} attaches to the (created, not yet started) container and then starts it, so no output
 * is lost. The container has no TTY, so Docker sends its standard output and standard error as separate
 * frames: standard output is cut into lines, each one a message; standard error goes, line by line, to the
 * handler given to the constructor. Outgoing messages are queued, one line each, on a stream that Docker
 * reads and copies to the container's standard input.
 *
 * <p>When the attachment ends without {@link #closeGracefully} having been called, the container has
 * exited: {@link #awaitTermination()} then fails with an {@link AcpConnectionException} naming the exit
 * code, which is how the session notices a crash. Closing does not stop or remove the container; the session
 * does that through {@link DockerRuntime}.
 */
final class ContainerAcpTransport implements AcpClientTransport {

    private static final Logger log = LoggerFactory.getLogger(ContainerAcpTransport.class);

    private static final Duration ATTACH_WAIT = Duration.ofSeconds(30);

    private static final Duration EXIT_WAIT = Duration.ofSeconds(5);

    private final DockerRuntime docker;

    private final String containerId;

    private final Consumer<String> stderrHandler;

    private final Consumer<String> onUnexpectedEnd;

    private final Runnable afterStart;

    private final AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();

    private final Sinks.Many<JSONRPCMessage> inbound = Sinks.many().unicast().onBackpressureBuffer();

    private final Sinks.One<Void> termination = Sinks.one();

    private final StdinPipe stdin = new StdinPipe();

    private final AtomicBoolean connected = new AtomicBoolean();

    private final AtomicBoolean stopping = new AtomicBoolean();

    private final Object writeLock = new Object();

    private volatile boolean closedLocally;

    private volatile ResultCallback<Frame> attachment;

    private volatile Consumer<Throwable> exceptionHandler = e -> log.warn("ACP transport error: {}", e.toString());

    /**
     * @param stderrHandler receives each line the agent writes to standard error
     * @param onUnexpectedEnd told, with the reason, when the container ends without the transport having been
     * closed; it runs on a Docker client thread
     * @param afterStart run once the container has started and before any message is sent: the session's
     * secrets are written then, while the entrypoint waits for them
     */
    ContainerAcpTransport(DockerRuntime docker, String containerId, Consumer<String> stderrHandler,
            Consumer<String> onUnexpectedEnd, Runnable afterStart) {
        this.docker = docker;
        this.containerId = containerId;
        this.stderrHandler = stderrHandler;
        this.onUnexpectedEnd = onUnexpectedEnd;
        this.afterStart = afterStart;
    }

    @Override
    public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
        return Mono.defer(() -> {
            if (!connected.compareAndSet(false, true)) {
                return Mono.error(new IllegalStateException("The transport is already connected"));
            }
            return Mono.fromRunnable(() -> {
                inbound.asFlux()
                    .flatMap(message -> Mono.just(message).transform(handler))
                    .subscribe(ignored -> {
                    }, error -> log.warn("Inbound message processing ended with an error", error));
                Frames frames = docker.attach(containerId, stdin, new Frames());
                try {
                    if (!frames.awaitStarted(ATTACH_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new AcpConnectionException("Could not attach to the agent's container");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AcpConnectionException("Interrupted while attaching to the agent's container", e);
                }
                this.attachment = frames;
                docker.start(containerId);
                afterStart.run();
            });
        });
    }

    /** Receives the container's output frames and the end of the attachment. */
    private final class Frames extends ResultCallback.Adapter<Frame> {

        private final LineSplitter stdout = new LineSplitter();

        private final LineSplitter stderr = new LineSplitter();

        @Override
        public void onNext(Frame frame) {
            if (frame.getStreamType() == StreamType.STDERR) {
                stderr.feed(frame.getPayload(), stderrHandler);
            } else if (frame.getStreamType() == StreamType.STDOUT || frame.getStreamType() == StreamType.RAW) {
                stdout.feed(frame.getPayload(), ContainerAcpTransport.this::emitInbound);
            }
        }

        @Override
        public void onError(Throwable failure) {
            super.onError(failure);
            ended(failure);
        }

        @Override
        public void onComplete() {
            super.onComplete();
            ended(null);
        }
    }

    private void emitInbound(String line) {
        if (line.isBlank() || closedLocally) {
            return;
        }
        log.trace("RECV: {}", line);
        JSONRPCMessage message;
        try {
            message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, line);
        } catch (Exception e) {
            // the agent printed something that is not a message: report it and answer it, as the SDK does
            log.warn("Container {}: skipped an output line that is not a JSON-RPC message", DockerRuntime.shortId(containerId));
            exceptionHandler.accept(e);
            writeLine(AcpSchema.unreadableMessageResponse(jsonMapper, line));
            return;
        }
        if (!inbound.tryEmitNext(message).isSuccess() && !closedLocally) {
            log.error("Container {}: failed to enqueue an inbound {}", DockerRuntime.shortId(containerId),
                    message.getClass().getSimpleName());
        }
    }

    /** The attachment ended: the container exited, or the connection to Docker broke, or we closed it. */
    private void ended(Throwable failure) {
        inbound.tryEmitComplete();
        stdin.finish();
        if (closedLocally) {
            termination.tryEmitEmpty();
            return;
        }
        String reason;
        if (failure != null) {
            reason = "The connection to the agent's container failed: " + failure.getMessage();
        } else {
            Long exit = null;
            long deadline = System.nanoTime() + EXIT_WAIT.toNanos();
            while (exit == null && System.nanoTime() < deadline) {
                exit = docker.exitCode(containerId);
                if (exit == null) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            reason = exit == null ? "The agent's container closed its output"
                    : "The agent's container exited with code " + exit;
        }
        if (closedLocally) {
            termination.tryEmitEmpty();
            return;
        }
        log.info("Container {}: {}", DockerRuntime.shortId(containerId), reason);
        termination.tryEmitError(new AcpConnectionException(reason));
        onUnexpectedEnd.accept(reason);
    }

    @Override
    public Mono<Void> sendMessage(JSONRPCMessage message) {
        if (closedLocally || stdin.isFinished()) {
            return Mono.error(new AcpConnectionException("The transport is closed"));
        }
        writeLine(message);
        return Mono.empty();
    }

    private void writeLine(JSONRPCMessage message) {
        String json;
        try {
            // messages are delimited by newlines and must not contain any
            json = jsonMapper.writeValueAsString(message)
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
        } catch (IOException e) {
            throw new AcpConnectionException("A message could not be written: " + e.getMessage(), e);
        }
        log.trace("SEND: {}", json);
        synchronized (writeLock) {
            stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        this.exceptionHandler = handler;
    }

    /**
     * Ends the container's standard input (an ACP agent exits when its input ends) and detaches. The
     * container itself is stopped and removed by the session.
     */
    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            if (!stopping.compareAndSet(false, true)) {
                return;
            }
            closedLocally = true;
            inbound.tryEmitComplete();
            stdin.finish();
            ResultCallback<Frame> current = attachment;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException | RuntimeException e) {
                    log.debug("Detaching from container {} failed: {}", DockerRuntime.shortId(containerId), e.toString());
                }
            }
            termination.tryEmitEmpty();
        });
    }

    @Override
    public Mono<Void> awaitTermination() {
        return termination.asMono();
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return jsonMapper.convertValue(data, typeRef);
    }

    /** Cuts a byte stream that arrives in arbitrary chunks into UTF-8 lines. Used from one thread at a time. */
    static final class LineSplitter {

        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        void feed(byte[] bytes, Consumer<String> lines) {
            if (bytes == null) {
                return;
            }
            int start = 0;
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == '\n') {
                    pending.write(bytes, start, i - start);
                    String line = pending.toString(StandardCharsets.UTF_8);
                    pending.reset();
                    lines.accept(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
                    start = i + 1;
                }
            }
            pending.write(bytes, start, bytes.length - start);
        }
    }

    /**
     * The container's standard input as Docker reads it: an input stream fed by {@link #write}, which never
     * blocks the writer. Unlike {@code PipedInputStream} it does not care which threads read and write.
     */
    static final class StdinPipe extends InputStream {

        private static final byte[] END = new byte[0];

        private final LinkedBlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();

        private volatile boolean finished;

        private byte[] current;

        private int position;

        void write(byte[] bytes) {
            if (!finished) {
                chunks.add(bytes);
            }
        }

        boolean isFinished() {
            return finished;
        }

        /** Ends the stream once what was written before has been read. */
        void finish() {
            if (!finished) {
                finished = true;
                chunks.add(END);
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (current == null || position >= current.length) {
                if (current == END) {
                    return -1;
                }
                try {
                    current = chunks.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
                position = 0;
            }
            int n = Math.min(length, current.length - position);
            System.arraycopy(current, position, buffer, offset, n);
            position += n;
            return n;
        }

        @Override
        public int available() {
            return current == null || current == END ? 0 : current.length - position;
        }

        @Override
        public void close() {
            finish();
        }
    }
}
