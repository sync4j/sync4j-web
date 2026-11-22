package com.fathzer.sync4j.web.plugin;

import java.io.IOException;
import java.util.Objects;

/**
 * The exception a plugin throws in {@link com.fathzer.sync4j.web.sync.SyncListener#beforeSync(com.fathzer.sync4j.web.sync.SyncRequest)} to refuse a synchronization.
 * <br>Its message is displayed to the user: it should tell why the synchronization is refused, without the details of the server.
 * Any other exception is considered as an error of the server: the user only receives a reference to find it in the log.
 */
public class SyncRefusedException extends IOException {
    private static final long serialVersionUID = 1L;
    /** What the user receives: a {@link Message} (translated by the page) or a string. */
    private final transient Object userMessage;

    /**
     * Constructor.
     * @param message the reason of the refusal, translated by the page (see {@link Message})
     */
    public SyncRefusedException(Message message) {
        super(Objects.requireNonNull(message).key() + message.args());
        this.userMessage = message;
    }

    /**
     * Constructor.
     * @param message the reason of the refusal (it is not translated)
     */
    public SyncRefusedException(String message) {
        super(Objects.requireNonNull(message));
        this.userMessage = message;
    }

    /**
     * Gets the message sent to the user.
     * @return a {@link Message} or a string
     */
    public Object getUserMessage() {
        return userMessage;
    }
}
