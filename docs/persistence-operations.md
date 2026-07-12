# Encrypted room recovery operations

The recovery subsystem is a single-server durability layer. Each room owns one encrypted WAL containing its lobby and optional active game. It is not a historical hand archive and must not be shared by two application instances.

## Configuration

Persistence is off by default for developer and legacy tests. A deployed instance should set:

```text
POKER_PERSISTENCE_ENABLED=true
POKER_PERSISTENCE_DIRECTORY=/var/lib/poker/wal
POKER_PERSISTENCE_CURRENT_KEY_ID=current
POKER_PERSISTENCE_KEY_CURRENT=<base64-encoded 32-byte key>
```

Generate a key without writing plaintext key material into the WAL volume:

```bash
openssl rand -base64 32
```

Supply keys through the deployment secret manager. Do not put keys in the image, repository, WAL directory, application logs, or backups of the WAL volume. Enabling persistence without a writable directory, current key ID, or valid 32-byte AES key fails startup.

Optional bounds are `POKER_PERSISTENCE_COMPACT_AFTER_RECORDS` (default `1000`) and `POKER_PERSISTENCE_COMPACT_AFTER_BYTES` (default `16777216`). Compaction uses the same per-room serialization boundary as live mutations, flushes a replacement under the current key, and atomically replaces the old WAL. Other rooms continue independently.

## Docker volume

The image declares `/var/lib/poker/wal` as its recovery volume. Mount one persistent volume into exactly one writer:

```bash
docker run --rm -p 8080:8080 \
  -v poker-wal:/var/lib/poker/wal \
  -e POKER_PERSISTENCE_ENABLED=true \
  -e POKER_PERSISTENCE_KEY_CURRENT="$POKER_PERSISTENCE_KEY_CURRENT" \
  poker-backend
```

Do not run rolling replicas against the same volume. Stop the writer before copying or restoring a volume backup. A filesystem snapshot must contain the whole directory at one point in time; copying individual active WALs while writes continue is not a supported backup.

## Rotation and retirement

1. Keep the previous key in the read keyring and add a new 32-byte key.
2. Set the new key ID as current. Every new record uses it; existing WALs remain readable with the previous key.
3. Let active rooms compact, or lower the thresholds temporarily. Compaction rewrites retained state under the current key.
4. Verify no retained WAL needs the previous key by starting a maintenance/canary instance against a copy of the volume with only the new key.
5. Retire the previous secret only after that verification.

For multiple key IDs, provide the map with `SPRING_APPLICATION_JSON`, for example:

```json
{"poker":{"persistence":{"enabled":true,"current-key-id":"2026-07","keys":{"2026-06":"<old-base64>","2026-07":"<new-base64>"}}}}
```

Removing an old key too early fails startup. The server never guesses, skips, or silently discards an unreadable room.

## Recovery and failure policy

Startup authenticates every complete frame before restoring traffic readiness. A final incomplete frame or uncommitted `PREPARE` is treated as a crash-torn tail and ignored. Missing keys, wrong keys, authentication failures, reordered/copied records, corruption in the committed prefix, an unreadable directory, or an unsupported schema fail startup.

A prepare write/flush failure rejects the mutation before domain state changes. A commit write/flush failure suppresses queued broadcasts and success, marks persistence unhealthy, and rejects later mutations until restart and recovery. The application does not attempt to roll back mutated memory after a failed commit because the safe response is to remove the unhealthy process from service.

Actuator health includes `pokerPersistenceHealth` with `recoveryComplete`, `activeWalCount`, and a sanitized `lastStorageError`. It never includes keys or decrypted state. Alert on DOWN status, repeated restarts, compaction errors, or unexpected WAL growth.

## Retention and deletion

The WAL retains only live recovery state. A permanent lobby destruction or completed game cleanup commits a tombstone and deletes its WAL. Startup recognizes an interrupted deletion and will not resurrect the room. Backups may retain older deleted WALs, so apply the same retention and access controls to backup media and expire backups according to the product's privacy policy.

## Restore drill

Regularly rehearse this sequence on an isolated host:

1. Stop the writer and snapshot the WAL volume plus its separately managed keyring.
2. Start one instance with the copied volume and correct keys.
3. Confirm readiness and persistence health are UP.
4. Inspect representative public/private game state and reconnect through STOMP.
5. Continue an action, verify a new committed record, then destroy a test room and verify its WAL disappears.

Never test a restore by pointing a second writer at the production volume.
