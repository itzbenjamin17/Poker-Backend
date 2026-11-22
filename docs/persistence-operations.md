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

`/var/lib/poker/wal` is the path **inside the container**. When mounting a named Docker volume (such as `poker-wal`), Docker stores the underlying files on the host under `/var/lib/docker/volumes/<volume-name>/_data` rather than `/var/lib/poker/wal` on the host; use a host bind mount (for example `-v /opt/poker/wal:/var/lib/poker/wal`) if you want files directly on the host filesystem. Verify the host mount path with `docker inspect <container> --format '{{json .Mounts}}'` and inspect live WAL files inside the container with `docker exec <container> ls -la /var/lib/poker/wal` (the directory is empty when no rooms or active games exist). If you deploy with an `.env` file, make sure `POKER_PERSISTENCE_DIRECTORY` matches the mounted container path (`/var/lib/poker/wal`) rather than `.env.example`'s local default (`./data/poker-wal`).

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

Startup authenticates every complete frame before restoring traffic readiness. During startup recovery, the application publishes `ReadinessState.REFUSING_TRAFFIC`. The `persistenceRecoveryTrafficGate` servlet filter (running at `HIGHEST_PRECEDENCE`) blocks non-health incoming requests with HTTP 503 `SC_SERVICE_UNAVAILABLE` until recovery completes. A final incomplete frame or uncommitted `PREPARE` is treated as a crash-torn tail and ignored. Missing keys, wrong keys, authentication failures, an unreadable directory, or global storage failures fail startup. However, single corrupted room WALs, record framing corruption, or unparseable snapshot payloads are isolated: the offending file is quarantined to `<roomId>.wal.corrupted` so valid rooms recover cleanly without crashing the server. Failed WAL deletions during recovery (for tombstones or expired rooms/games) log a warning and skip without quarantining the file or aborting startup.

Runtime failures during prepare, state serialization, commit, or maintenance do not stop valid gameplay. Store health is isolated per room: a write failure disables ordinary appends only for the affected room until a checkpoint or WAL deletion clears it, while unaffected rooms continue appending, compacting, and deleting normally. Accepted mutations release queued broadcasts and timers once, retain the latest accepted serialized image or deletion tombstone per room, and retry saving every five seconds and on later accepted mutations. Retries use the same per-room lock and checkpoint uncertain tails without replaying commands or effects. In-memory aggregate rollback restores RAM state if a mutation throws an exception. If the process stops before saving catches up, accepted in-memory changes for unsaved rooms can be lost. Global infrastructure failures and missing keys still fail startup; traffic remains gated until startup recovery completes.

The save format isn't versioned; snapshots are parsed strictly (rejecting unknown or missing fields) and persisted with public `stateRevision` and frozen terminal game-end results for post-game review. Startup recovery isolates unparseable or incompatible snapshots by moving them to `<roomId>.wal.corrupted` rather than aborting the application context. After any change to `persistence/snapshot` or the WAL frame layout, delete `POKER_PERSISTENCE_DIRECTORY`.

Actuator health includes `pokerPersistenceHealth` reporting `recoveryComplete`, `persistenceHealthy`, `mode`, `unsavedRoomCount`, `activeWalCount`, and a sanitized `lastStorageError`. `persistenceHealthy` reports overall store health (`true` only when startup recovery succeeded and no room has an outstanding write failure), so any unsaved room switches `mode` to `MEMORY_ONLY` while gameplay health remains UP. It never includes keys or decrypted state. Alert on `MEMORY_ONLY`, growing unsaved-room count, repeated startup failures, compaction errors, or unexpected WAL growth. Runtime storage degradation alone does not make gameplay health DOWN.

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
