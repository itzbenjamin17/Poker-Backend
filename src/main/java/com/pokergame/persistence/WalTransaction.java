package com.pokergame.persistence;

import java.util.UUID;

public record WalTransaction(String roomId, UUID transactionId, long prepareSequence) {
}
