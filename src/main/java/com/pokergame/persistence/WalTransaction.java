package com.pokergame.persistence;

import java.util.UUID;

/**
 * Identifies the prepare record that a commit must immediately follow. Carrying the
 * sequence and transaction ID prevents a stale or interleaved mutation from being
 * paired with the wrong state image.
 *
 * @param roomId         room owning the WAL transaction
 * @param transactionId identity shared by prepare and commit records
 * @param prepareSequence sequence assigned to the prepare record
 */
public record WalTransaction(String roomId, UUID transactionId, long prepareSequence) {
}
