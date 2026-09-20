package com.pokergame.persistence.wal;

import java.util.UUID;

/**
 * Links a "commit" action to the "prepare" action that came just before it. 
 * By keeping track of these IDs and sequences, we make sure we don't accidentally
 * mix up older changes or changes from different save operations, ensuring
 * the poker game state is always saved perfectly in order.
 *
 * @param roomId         the poker room being saved
 * @param transactionId  the unique ID that connects a prepare and its commit
 * @param prepareSequence the order number given to the prepare action
 */
public record WalTransaction(String roomId, UUID transactionId, long prepareSequence) {
}
