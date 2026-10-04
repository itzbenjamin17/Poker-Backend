# Backend Logging Architecture and Operations Guide

This document defines logging conventions, architecture, retention policies, diagnostic commands, and operational procedures for the Poker backend.

---

## 1. Overview

The backend uses **SLF4J** backed by **Logback** (Spring Boot 4.0.6 default) to deliver structured, correlated diagnostic information.
- **Outputs:** Dual appenders: standard console (`STDOUT`) and a rolling file (`poker.log`).
- **Format:** Plain text with fixed-width timestamp and MDC correlation fields (no JSON serialization overhead).
- **Correlation:** Every thread processing room, player, or websocket actions carries MDC context (`roomId`, `playerName`, `sessionId`).

---

## 2. Levels and Conventions

### Log Levels

| Level | Intended Usage | Examples |
|---|---|---|
| **ERROR** | Unrecoverable failures, invariant violations, deserialization errors. Requires immediate attention. | Identity mismatch during persistence recovery, corrupt WAL image deserialization failure. |
| **WARN** | Recoverable anomalies, client abuse, rate limits, abnormal drops, or duplicate events. | 429 Rate limit exceeded, 413 Payload too large, STOMP CONNECT without credentials, duplicate showdown call on resolved hand. |
| **INFO** | Significant state transitions occurring at most once per hand, room, session, or lifecycle step. | Persistence recovery summary, player disconnect/reconnect state transitions, showdown winner/split pot summaries, side-pot resolution, uncalled chip refunds. |
| **DEBUG** | Per-action execution steps, scheduling details, lock acquisitions, stomp subscribe/unsubscribe events. | Individual decision application, scheduled timer execution/stale checks, WAL commit duration and bytes, token validation exception classes. |
| **TRACE** | Fine-grained evaluation loops on hot paths. Extremely verbose; disabled in production. | Hand evaluator card comparisons (`compareStraight`, `compareFlush`, `compareHighCard`, kicker comparisons, tie checks). |

### Security & Privacy Rules
- **NEVER LOG SENSITIVE SECRETS:** Absolutely no JWT strings, passwords, or AES encryption keys.
- **NEVER LOG LIVE HOLE CARDS:** Live player hole cards must never be logged. Card values may only be logged at `DEBUG` or `TRACE` after a hand reaches showdown.
- **Client IP Handling:** Client IPs are resolved via `SecurityUtils.getClientIp(request, trustProxy)` respecting the reverse proxy configuration (`poker.security.trust-proxy`), ensuring the actual client IP is logged rather than the upstream proxy loopback (`127.0.0.1`).
- **Parameterized Logging:** Always use parameterized messages (`logger.info("Room {}", roomId)`), never string concatenation (`+`).
- **Costly Argument Guards:** Wrap expensive argument calculations or string building in `logger.isDebugEnabled()` or `logger.isTraceEnabled()`.

---

## 3. Where Logs Are Stored (Production VPS)

### Production Deployment Layout
The production VPS runs a Docker Compose deployment located at `~/poker-app`. Watchtower recreates the container on every deploy, which would destroy container-local files. Therefore:
- The container writes to `/app/logs/poker.log` (`ENV LOG_FILE=/app/logs/poker.log`).
- A host bind mount maps `/opt/poker/logs` on the host to `/app/logs` inside the container:
  ```yaml
  backend:
    environment:
      LOG_FILE: /app/logs/poker.log
    volumes:
      - poker-wal-data:/var/lib/poker/wal
      - /opt/poker/logs:/app/logs
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
  ```
- **Single File Architecture:** There is **one file (`poker.log`) for the entire application**, NOT one per room. Room-level isolation is achieved through MDC search (`room=<roomId>`).
- **Archive Naming:** Rotated logs are saved as:
  `poker.log.YYYY-MM-DD.i.gz` (e.g., `poker.log.2026-10-04.0.gz`).

---

## 4. Retention and Rotation

Configured in `src/main/resources/application.properties`:

```properties
logging.file.name=${LOG_FILE:/app/logs/poker.log}
logging.logback.rollingpolicy.file-name-pattern=${LOG_FILE:/app/logs/poker.log}.%d{yyyy-MM-dd}.%i.gz
logging.logback.rollingpolicy.max-file-size=20MB
logging.logback.rollingpolicy.max-history=14
logging.logback.rollingpolicy.total-size-cap=500MB
logging.logback.rollingpolicy.clean-history-on-start=true
```

### Explanation of Properties
- `max-file-size=20MB`: Files roll whenever they exceed 20 MB, keeping individual files small enough for fast searching and parsing without memory exhaustion.
- `max-history=14`: Archived logs are kept for **14 days**. This covers standard retrospective bug investigations (e.g., "an issue occurred last weekend") while limiting the storage duration of user names and IPs for privacy.
- `total-size-cap=500MB`: An absolute disk safety ceiling. If an unexpected logging storm occurs, old archives are purged even if they are under 14 days old, ensuring the VPS disk never fills.
- `clean-history-on-start=true`: Prunes expired archive logs on application startup.
- **Gzip Compression (`.gz`):** Rotated archives are gzip-compressed automatically, reducing disk consumption by ~90%.

---

## 5. Log Line Format

### Patterns
- **File Pattern:**
  `%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [room=%X{roomId:-} player=%X{playerName:-}] %logger{25} - %msg%n`
- **Console Pattern:**
  `%d{HH:mm:ss.SSS} %-5level [room=%X{roomId:-}] %logger{20} - %msg%n`

### Annotated Example Line
```
2026-10-04 15:47:06.429 INFO  [http-nio-8080-exec-1] [room=abc123 player=Benjamin] c.p.model.Game - Showdown single winner: Benjamin with rank=ONE_PAIR
```
- `2026-10-04 15:47:06.429`: Millisecond-precision UTC/local timestamp.
- `INFO`: Severity level.
- `[http-nio-8080-exec-1]`: Thread name executing the operation.
- `[room=abc123 player=Benjamin]`: MDC context; blank (`room= player=`) when unauthenticated or in global operations.
- `c.p.model.Game`: Abbreviated logger class name (`com.pokergame.model.Game`).
- `Showdown single winner: Benjamin with rank=ONE_PAIR`: The message payload.

---

## 6. Mapped Diagnostic Context (MDC)

### Keys (`com.pokergame.util.MdcKeys`)
- `roomId`: The 6-character room identifier.
- `playerName`: Sanitized player name.
- `sessionId`: WebSocket STOMP session ID.

### Where MDC Is Populated and Cleaned
1. **REST Requests (`JwtAuthenticationFilter`):** Extracted from verified JWT claims; populated on entry, cleared in `finally`.
2. **WebSocket Inbound Messages (`WebSocketAuthInterceptor`):** Extracted from `PlayerPrincipal` during STOMP frame handling in `preSend`, cleaned up in `afterSendCompletion`.
3. **STOMP Event Listeners (`WebSocketEventListener`):** Managed safely via `MdcUtils.runWithMdc(...)` for connection, disconnection, subscription, and unsubscription events.
4. **Scheduled Async Tasks & Timers (`AsyncConfiguration`):** Propagated automatically to execution worker threads using `MdcTaskDecorator` configured on `ThreadPoolTaskScheduler` and `ThreadPoolTaskExecutor`, ensuring clean domain logic without manual boilerplate in `GameLifecycleService`.
5. **Durable Transactions (`DurableMutationAspect`):** Sets `roomId` on mutation entry and cleans up in the outer `finally` block.

> [!IMPORTANT]
> **Always clear MDC in a `finally` block.** Since threads in thread pools are reused, failing to clear MDC causes context leakage across unrelated requests.

---

## 7. Searching Logs When Debugging

All commands run directly on the VPS against `/opt/poker/logs` or via Docker:

### Live Tail & Follow
```bash
tail -f /opt/poker/logs/poker.log
```

### Filter by Specific Room
```bash
grep 'room=abc123' /opt/poker/logs/poker.log
```

### Filter by Specific Player
```bash
grep 'player=Benjamin' /opt/poker/logs/poker.log
```

### Errors and Warnings Only
```bash
grep -E 'WARN|ERROR' /opt/poker/logs/poker.log
```

### Errors with Stack Trace Context
```bash
grep -A 25 'ERROR' /opt/poker/logs/poker.log
```

### Date or Time Window Search
```bash
# Between 14:00 and 14:59 on 2026-10-04
grep '^2026-10-04 14:' /opt/poker/logs/poker.log
```

### Searching Compressed Archive Logs
```bash
zgrep 'room=abc123' /opt/poker/logs/poker.log.*.gz
```

### Blocked Abuse by Client IP (Rate Limit / Payload Size)
```bash
grep -E 'Rate limit exceeded|Payload size exceeded' /opt/poker/logs/poker.log
```

### Stale Timers or Ignored Delayed Tasks
```bash
grep 'Ignoring stale task' /opt/poker/logs/poker.log
```

### Reconnection and Disconnect Lifecycle
```bash
grep -E 'Player .* disconnected|Player .* reconnected|grace expiry|disconnect scheduled' /opt/poker/logs/poker.log
```

### Startup Recovery Summary
```bash
grep -E 'Starting persistence recovery|Recovery complete' /opt/poker/logs/poker.log
```

### Count Warnings per Day
```bash
grep 'WARN' /opt/poker/logs/poker.log | cut -c 1-10 | sort | uniq -c
```

### Disk Usage of Log Files
```bash
du -sh /opt/poker/logs/*
```

### Docker Native Logs Alternative
```bash
docker logs --tail 200 poker-backend
docker logs --since 30m poker-backend
```

---

## 8. Turning DEBUG On Temporarily

To debug persistence or websocket connectivity in production without modifying code:
1. Edit `/opt/poker/.env` on the host:
   ```bash
   LOG_LEVEL_PERSISTENCE=DEBUG
   LOG_LEVEL_WEBSOCKET=DEBUG
   ```
2. Recreate the backend service:
   ```bash
   docker compose up -d backend
   ```
3. Reproduce the scenario and inspect the log output.
4. **Revert when finished** (reset variables to `INFO` and run `docker compose up -d backend`) to avoid excessive disk I/O and large log files.

---

## 9. Noise Suppression

### Spring WebSocket 30-Minute Broker Stats
By default, Spring WebSocket outputs a periodic informational message every 30 minutes:
`WebSocketMessageBrokerStats - MessageBroker[50 subscriptions, 1000 messages...]`.

### Suppressing via Environment Variable
To suppress this message in deployment without changing codebase configuration:
Spring converts environment variable names to lowercase and replaces underscores with dots. Because package names are dot-separated, define the package-level log override in `.env`:
```bash
LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB_SOCKET_CONFIG=WARN
```

Alternatively, periodic stats logging can be disabled in `WebSocketConfig` via `stats.setLoggingPeriod(0)` or by setting `logging.level.org.springframework.web.socket.config.WebSocketMessageBrokerStats=WARN`.

---

## 10. Operational Notes

- **Reverse Proxy IP Trust (`TRUST_PROXY=true`):** In production behind an Nginx or Traefik reverse proxy, `TRUST_PROXY=true` must be set in `.env` so `SecurityUtils.getClientIp` extracts the real client IP from `X-Forwarded-For`.
- **Privacy & Redaction:** Production logs contain player display names, room IDs, and client IPs. Always redact sensitive identifiers before attaching logs to public GitHub issues or sharing diagnostics.
- **Local Dev vs Tests:**
  - Local development defaults to `LOG_FILE=./logs/poker.log` (automatically created, git-ignored).
  - Test suites run with `logging.file.name=` in `src/test/resources/application-test.properties` so tests do not pollute disk with log files.
- **WAL Storage Alignment:**
  `POKER_PERSISTENCE_DIRECTORY=/var/lib/poker/wal` in `.env` must match the Docker Compose volume mount (`poker-wal-data:/var/lib/poker/wal`).
  - Verify volume mounting with:
    ```bash
    docker inspect poker-backend --format '{{json .Mounts}}'
    ```
  - A path mismatch will silently place WAL files into an anonymous Docker volume that is discarded on redeployment.
- **Compose Source of Truth:**
  The `docker-compose.yml` resides on the VPS host. When changing volume mounts or environment variables, update the VPS compose file or your centralized deployment repository.

---

## 11. Checklist for Adding a New Log Line

Before adding any new log statement, verify:
- [ ] **SLF4J Logger:** Uses `private static final Logger logger = LoggerFactory.getLogger(YourClass.class);`.
- [ ] **No Secret Leaks:** No JWT tokens, passwords, encryption keys, or live hole cards are printed.
- [ ] **Appropriate Level:**
  - `ERROR`: Unhandled exceptions, corrupted state, fatal errors.
  - `WARN`: Expected anomalies, rate limits, rejections, stale drops.
  - `INFO`: Milestones (hand start, showdown, pot split, connect/reconnect).
  - `DEBUG`: Per-action mechanics, scheduling callbacks, WAL syncs.
  - `TRACE`: Intensive hand comparison loops.
- [ ] **Parameterized Output:** Uses `{}` placeholders instead of string concatenation `+`.
- [ ] **Cost Guard:** Enclosed in `if (logger.isDebugEnabled())` or `if (logger.isTraceEnabled())` if building arguments involves collection transformation or string formatting.
- [ ] **MDC Lifecycle:** If running in a background thread or scheduler, set `MDC.put(MdcKeys.ROOM_ID, ...)` on entry and `MDC.remove(...)` in a `finally` block.
