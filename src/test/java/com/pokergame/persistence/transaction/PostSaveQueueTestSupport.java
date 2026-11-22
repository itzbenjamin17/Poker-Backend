package com.pokergame.persistence.transaction;

/**
 * Test-only handle that opens a {@link PostSaveActions} queue on the current thread so tests in
 * other packages can observe what a service defers until saving finishes.
 *
 * <p>{@code PostSaveActions.begin}, {@code executeAll}, and {@code clear} are package-private
 * because only {@code DurableMutationAspect} drives the queue in production; this class exposes
 * the same lifecycle to tests. Closing the handle discards any actions that were not run.</p>
 */
public final class PostSaveQueueTestSupport implements AutoCloseable {
    private PostSaveQueueTestSupport(String roomId) {
        PostSaveActions.begin(roomId);
    }

    /**
     * Opens a post-save queue for {@code roomId} on the current thread.
     *
     * @param roomId the room whose actions are queued
     * @return a handle that must be closed to discard the queue
     */
    public static PostSaveQueueTestSupport open(String roomId) {
        return new PostSaveQueueTestSupport(roomId);
    }

    /** Runs every queued action in order, as a successful commit does. */
    public void executeAll() {
        PostSaveActions.executeAll();
    }

    /** Discards the queue without running its actions, as a failed mutation does. */
    @Override
    public void close() {
        PostSaveActions.clear();
    }
}
