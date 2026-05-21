# ♠️ Poker Backend - Real-time Game Engine

A **Java 25 + Spring Boot 4.0** backend for multiplayer poker, featuring real-time game state management, WebSocket communication, JWT authentication, reconnect handling, and comprehensive Texas Hold'em logic.

**Purpose:** Showcase project demonstrating enterprise backend patterns, real-time systems, and clean architecture with a focus on security and stability.

Companion frontend showcase: ([Repo](https://github.com/itzbenjamin17/Poker-Frontend))

## 📋 Table of Contents

- [Demo Preview](#demo-preview)
- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
- [Key Features](#key-features)
- [Security & Hardening](#security--hardening)
- [API Reference](#api-reference)
- [Build & Verification](#build--verification)
- [Future Roadmap](#future-roadmap)

## 🚀 Demo Preview

### Quick Start (Local)

```bash
# Clone the repository
git clone https://github.com/itzbenjamin17/Poker.git
cd Poker/

# Build and Run (using Maven Wrapper)
.\mvnw.cmd clean package
java -jar target/Poker-0.0.1-SNAPSHOT.jar

# Server starts on: http://localhost:8080
```

**Companion frontend URL:** http://localhost:5173

## 🛠️ Tech Stack

| Technology      | Version | Purpose                             |
| --------------- | ------- | ----------------------------------- |
| **Java**        | 25      | Language (Records, Sequences, etc)  |
| **Spring Boot** | 4.0.6   | Framework with managed dependencies |
| **JJWT**        | 0.13.0  | Stateless JWT authentication        |
| **Bucket4j**    | 8.10.1  | API & WebSocket Rate Limiting       |
| **JUnit 5**     | 5.11.4  | Testing and verification            |

## 🏗️ Architecture

The engine follows a strict **Layered Architecture** with unidirectional dependency flow:

- **Controller Layer:** REST and STOMP endpoints for lobby and game interaction.
- **Service Layer:** Orchestrates business logic (Game Lifecycle, Betting Rounds, Side Pots).
- **Model Layer:** Pure domain objects (Game, Deck, Player) with thread-safe state management.
- **Security Layer:** Interceptors and Filters for JWT validation, Rate Limiting, and Payload Sanitization.

## ✨ Key Features

- **Real-time Texas Hold'em:** Full game loop from Pre-flop to River with automated phase transitions.
- **Advanced Pot Management:** Support for Side Pots, Split Pots, and Uncalled Chip refunds.
- **Heads-up & Multiplayer:** Dynamic dealer and blind positioning for 2-6 players.
- **Resilient Connections:** 2-minute reconnect grace window allowing players to resume their seats after a drop.
- **Ready System:** Shared countdown for starting new hands after a showdown.

## 🛡️ Security & Hardening

The engine has undergone a dedicated hardening pass:

- **Structured Identity:** JWTs use structured claims (playerName, roomId) to prevent identity spoofing and cross-room impersonation.
- **Secure WebSocket Messaging:** Private data is delivered via Spring user-specific destinations (`/user/queue/private`), preventing predictability and eavesdropping.
- **Rate Limiting:** Protects REST endpoints (5 attempts/15min) and WebSocket actions (5 msgs/sec) with per-identity tracking.
- **Lifecycle Enforcement:** Strict state checks prevent late joins to active games and duplicate game starts.
- **JWT Protection:** Signed tokens required for all actions; mandatory environment secret length verification at startup.
- **Input Sanitization:** All text inputs are trimmed and validated early; malformed JSON and oversized payloads (10KB limit) are rejected.
- **Thread Safety:** Synchronized room mutations and atomic game initialisation ensure consistency under high concurrent load.

## ✅ Build & Verification

### Domain-Driven & Behavior-Focused Testing

The project adheres to strict testing guidelines designed to verify the mathematical correctness of our poker rules engine and the integrity of real-time communication flows:

- **Logic & Rules Assertions:** All tests verify concrete business outcomes (e.g. correct side-pot splits, blinds posts, refund schedules, action validation) rather than brittle, implementation-specific internal component details or getters.
- **Client-Centric Validation:** Integration tests simulate exact client behaviors and assert on the payload structures transmitted over STOMP/WebSocket channels, guaranteeing contract compliance with the React frontend.
- **Robust and Isolated Suites:** Concurrency, task execution, and WebSocket messaging are tested with proper synchronization to ensure deterministic outcomes without port or context collisions.

The project maintains a high-quality baseline with **500+ automated tests**:

```bash
# Run full suite (Unit + Integration)
.\mvnw.cmd test
```

### Key Verification Areas

- `SecurityHardeningIntegrationTest`: Verifies identity isolation, input sanitization, and lifecycle enforcement.
- `WebSocketSecurityTest`: Validates that private topics cannot be intercepted by other players.
- `ResiliencyIntegrationTest`: Validates disconnect/reconnect state restoration.
- `GameLifecycleIntegrationTest`: Full deal-to-showdown lifecycle automation.
- `HandEvaluatorServiceTest`: Mathematical correctness of poker hand evaluations (evaluating 7-card combinations down to best 5).

## 🗺️ Future Roadmap

- **Persistence:** Migration from in-memory state to PostgreSQL/JPA.
- **Profiles:** User accounts, historical statistics, and leaderboard support.
- **Scalability:** Distributed state using Redis and external STOMP brokers.

---

**Companion frontend showcase:** ([Repo](https://github.com/itzbenjamin17/Poker-Frontend)). Together they demonstrate end-to-end real-time gameplay architecture.
