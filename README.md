# ♠️ Poker Backend - Real-time Game Engine

A **Java 21 + Spring Boot 4.0** backend for multiplayer poker, featuring real-time game state management, WebSocket communication, JWT authentication, and comprehensive hand evaluation logic.

**Purpose:** Showcase project demonstrating enterprise backend patterns, real-time systems, and clean architecture.

Companion frontend showcase: ([Repo](https://github.com/itzbenjamin17/Poker-Frontend))

## 📋 Table of Contents

- [Demo Preview](#demo-preview)
- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
- [Project Structure](#project-structure)
- [Game Rules & Logic](#game-rules--logic)
- [API Reference](#api-reference)
- [WebSocket Channels](#websocket-channels)
- [Authentication](#authentication)
- [Build & Demo Commands](#build--demo-commands)
- [Validation Evidence](#validation-evidence)
- [Future Enhancements](#future-enhancements)

## 🚀 Demo Preview

### Local Run (Windows)

```bash
# Build the project
.\mvnw.cmd clean package

# Run the application
java -jar target/Poker-0.0.1-SNAPSHOT.jar

# Or run directly without building JAR
.\mvnw.cmd spring-boot:run

# Server running on: http://localhost:8080
```

### Local Run (Mac/Linux)

```bash
# Build the project
./mvnw clean package

# Run the application
java -jar target/Poker-0.0.1-SNAPSHOT.jar

# Or run directly without building JAR
./mvnw spring-boot:run

# Server running on: http://localhost:8080
```

> 💡 **Recommended**: Use `mvnw` (Mac/Linux) or `mvnw.cmd` (Windows) — the Maven Wrapper. No global Maven installation required. If you have Maven installed globally, you can use `mvn` instead.

### IDE Run

```bash
# IntelliJ/Eclipse/VS Code: Open Poker/ folder to inspect or run the demo
# Run: src/main/java/com/pokergame/PokerApplication.java

# Server running on: http://localhost:8080
```

**Companion frontend URL (when running locally):** http://localhost:5173

## 🛠️ Tech Stack

| Technology      | Version | Purpose                             |
| --------------- | ------- | ----------------------------------- |
| **Java**        | 21      | Language                            |
| **Spring Boot** | 4.0.1   | Framework with managed dependencies |
| **JJWT**        | 0.13.0  | JWT token handling                  |

## 🏗️ Architecture

### Layered Architecture

```
┌─────────────────────────────────────────────────────┐
│  Controller Layer (REST + WebSocket endpoints)      │
│  ├── RoomController (REST)                          │
│  ├── GameController (WebSocket)                     │
│  └── ApiController (Unified gateway)                │
└────────────────────┬────────────────────────────────┘
                     │
┌─────────────────────▼────────────────────────────────┐
│  Service Layer (Business Logic)                     │
│  ├── RoomService (Room lifecycle)                   │
│  ├── GameLifecycleService (Hand management)         │
│  ├── GameStateService (State queries)               │
│  ├── PlayerActionService (Action validation)        │
│  └── HandEvaluatorService (Hand ranking)            │
└────────────────────┬────────────────────────────────┘
                     │
┌─────────────────────▼────────────────────────────────┐
│  Model Layer (Domain Objects)                       │
│  ├── Game (Game state, phases, betting)             │
│  ├── Room (Lobby management)                        │
│  ├── Player (Player state, chips, cards)            │
│  ├── Card (Immutable: Rank + Suit)                  │
│  └── Deck (52-card deck with shuffle)               │
└─────────────────────────────────────────────────────┘
```

> 💡 **Separation of Concerns**: Each layer has a single responsibility. Controllers handle routing, services handle logic, models handle state. Easy to test and maintain.

## 📁 Project Structure

```
src/main/java/com/pokergame/
├── PokerApplication.java          # Spring Boot entry point
│
├── controller/
│   ├── RoomController.java         # REST: /room, /api/room
│   ├── GameController.java         # REST: /game | WebSocket: /app/{gameId}/action
│   └── ApiController.java          # REST: /api (unified)
│
├── service/
│   ├── RoomService.java            # Room creation, join, leave
│   ├── GameLifecycleService.java   # Game initialization, phases
│   ├── GameStateService.java       # Game state queries
│   ├── PlayerActionService.java    # Action validation & execution
│   └── HandEvaluatorService.java   # Poker hand ranking
│
├── model/
│   ├── Game.java                   # Game state, betting, phases
│   ├── Room.java                   # Room/lobby management
│   ├── Player.java                 # Player state, chips, cards
│   ├── Card.java                   # Immutable card (Rank + Suit)
│   ├── Deck.java                   # 52-card deck, shuffle logic
│   └── HandEvaluationResult.java   # Hand ranking result
│
├── enums/
│   ├── GamePhase.java              # PRE_FLOP, FLOP, TURN, RIVER, SHOWDOWN
│   ├── PlayerAction.java           # FOLD, CHECK, CALL, BET, RAISE, ALL_IN
│   ├── HandRank.java               # NO_HAND to ROYAL_FLUSH (with rank values)
│   ├── PlayerStatus.java           # FOLDED, OUT, ACTIVE, ALL_IN
│   ├── Rank.java                   # A, 2-9, T (10), J, Q, K
│   ├── Suit.java                   # HEARTS, DIAMONDS, CLUBS, SPADES
│   └── ResponseMessage.java        # Standard response messages (GAME_STARTED, PLAYER_JOINED etc.)
│
├── dto/
│   ├── request/
│   │   ├── CreateRoomRequest.java   # Room creation payload
│   │   ├── JoinRoomRequest.java     # Room join payload
│   │   └── PlayerActionRequest.java # Player action (fold, bet, etc.)
│   │
│   └── response/
│       ├── ApiResponse.java         # Standard API response wrapper
│       ├── TokenResponse.java       # Auth token response
│       ├── RoomDataResponse.java    # Room details response
│       ├── ErrorResponse.java       # Error details response
│       ├── PublicGameStateResponse.java # Public game state DTO
│       ├── PublicPlayerState.java   # Public player info (no hole cards)
│       ├── PrivatePlayerState.java  # Private player info (with hole cards)
│       └── PlayerNotificationResponse.java # Player action notifications
│
├── config/
│   ├── SecurityConfig.java          # JWT auth, CORS, stateless
│   ├── WebSocketConfig.java         # STOMP endpoints, message broker
│   ├── WebSocketAuthInterceptor.java# JWT validation for WebSocket
│   ├── AsyncConfiguration.java      # Async/parallel processing
│   └── WebSocketEventListener.java  # Connection lifecycle events
│
├── security/
│   ├── JwtService.java              # Token generation & validation
│   └── JwtAuthenticationFilter.java  # JWT filter chain
│
├── exception/
│   ├── PokerException.java          # Base custom exception
│   ├── BadRequestException.java     # 400 Bad Request
│   ├── ResourceNotFoundException.java # 404 Not Found
│   ├── UnauthorisedActionException.java # 403 Forbidden
│   └── ControllerExceptionHandler.java  # Global exception handler
│
├── event/
│   ├── StartNewHandEvent.java       # Triggered to start new hand
│   ├── AutoAdvanceEvent.java        # Triggered to auto-advance phase
│   └── GameCleanupEvent.java        # Triggered on game end
│
├── listener/
    └── GameAsyncEventListener.java  # Listens for game events

```

## 🎰 Game Rules & Logic

### Game Phases

Texas Hold'em poker phases (managed by `GameLifecycleService`):

#### Pre-Flop

**Initial betting round after hole cards dealt**

- Each player receives 2 private cards (hole cards)
- Small blind posts small blind bet
- Big blind posts big blind bet
- UTG (Under The Gun) player starts betting
- Players can: Fold, Check, Call, Bet, Raise, All-in
- Phase ends when all players have matched the highest bet or folded

#### Flop

**First community card betting round**

- 3 community cards revealed on the table
- First active player (after dealer) starts betting
- Same betting rules as pre-flop
- Smallest bet size = Big Blind

#### Turn

**Second community card betting round**

- 1 additional community card revealed (4 total)
- First active player starts betting

#### River

**Final betting round**

- 1 final community card revealed (5 total)
- All players make final decisions

#### Showdown

**Winner determination**

- Remaining players reveal hole cards
- `HandEvaluatorService` evaluates each 5-card best hand
- Pot distributed to winner(s)
- Game ends or new hand begins

### Hand Rankings (Best to Worst)

Implemented in `HandRank` enum with numeric rank values:

| Rank | Name            | Example         | Points |
| ---- | --------------- | --------------- | ------ |
| 1    | Royal Flush     | A♠ K♠ Q♠ J♠ 10♠ | 10     |
| 2    | Straight Flush  | 9♥ 8♥ 7♥ 6♥ 5♥  | 9      |
| 3    | Four of a Kind  | K♦ K♣ K♠ K♥ 7♦  | 8      |
| 4    | Full House      | Q♠ Q♦ Q♥ 3♠ 3♦  | 7      |
| 5    | Flush           | A♣ J♣ 9♣ 7♣ 5♣  | 6      |
| 6    | Straight        | 10♠ 9♦ 8♥ 7♣ 6♠ | 5      |
| 7    | Three of a Kind | 8♠ 8♦ 8♥ K♠ 2♦  | 4      |
| 8    | Two Pair        | J♠ J♦ 5♠ 5♦ 3♥  | 3      |
| 9    | Pair            | 2♠ 2♣ K♠ Q♦ J♥  | 2      |
| 10   | High Card       | A♠ K♦ Q♥ J♣ 9♠  | 1      |

### Hand Evaluation Algorithm

The `HandEvaluatorService` evaluates all possible 5-card combinations from 7 cards (2 hole + 5 community):

```java
public HandEvaluationResult evaluateHand(List<Card> holeCards, List<Card> communityCards) {
  List<Card> allCards = new ArrayList<>();
  allCards.addAll(holeCards);
  allCards.addAll(communityCards);

  // Find best 5-card combination from 7 cards
  List<List<Card>> combinations = generateCombinations(allCards, 5);

  HandEvaluationResult bestHand = null;
  for (List<Card> combo : combinations) {
    HandEvaluationResult result = evaluateFiveCards(combo);
    if (bestHand == null || result.getRank().ordinal() > bestHand.getRank().ordinal()) {
      bestHand = result;
    }
  }
  return bestHand;
}
```

### Player Actions

Actions handled by `PlayerActionService`:

| Action     | Description                  | Requirements               |
| ---------- | ---------------------------- | -------------------------- |
| **FOLD**   | Discard hand, exit this hand | In-game, your turn         |
| **CHECK**  | Pass without betting         | No bet raised this round   |
| **CALL**   | Match current bet            | Sufficient chips           |
| **BET**    | Initiate or raise bet        | Specified amount available |
| **RAISE**  | Increase current bet         | Amount > current bet       |
| **ALL_IN** | Bet all remaining chips      | Any amount ≤ chip stack    |

## 🔌 API Reference

### REST Endpoints

All endpoints return JSON. Authentication via Bearer token in `Authorization` header.

#### Room Management

##### POST /api/room/create

**Create a new poker room**

**Request Body:**

```json
{
  "roomName": "High Stakes Game",
  "playerName": "AlexPoker",
  "maxPlayers": 6,
  "smallBlind": 5,
  "bigBlind": 10,
  "buyIn": 1000
}
```

**Response (200 OK):**

```json
{
  "message": "Room created successfully",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "roomId": "room-12345",
    "playerName": "AlexPoker"
  }
}
```

**Status Codes:**

- `200` - Room created
- `400` - Invalid parameters / room name already exists

##### POST /api/room/join

**Join an existing room**

**Request Body:**

```json
{
  "roomName": "High Stakes Game",
  "playerName": "BobPoker",
  "password": "optional-password"
}
```

**Response (200 OK):**

```json
{
  "message": "Successfully joined room",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "roomId": "room-12345",
    "playerName": "BobPoker"
  }
}
```

**Status Codes:**

- `200` - Joined successfully
- `404` - Room not found
- `403` - Room full
- `400` - Invalid password / invalid request

##### GET /api/room/:roomId

**Fetch room details**

**Response (200 OK):**

```json
{
  "roomId": "room-12345",
  "roomName": "High Stakes Game",
  "maxPlayers": 6,
  "buyIn": 1000,
  "smallBlind": 5,
  "bigBlind": 10,
  "hostName": "AlexPoker",
  "players": [
    {
      "name": "AlexPoker",
      "isHost": true,
      "joinedAt": "recently"
    },
    {
      "name": "BobPoker",
      "isHost": false,
      "joinedAt": "recently"
    }
  ],
  "currentPlayers": 2,
  "canStartGame": true
}
```

##### POST /api/room/:roomId/start-game

**Start the game (host only)**

**Response (200 OK):**

```json
{
  "message": "Game started successfully",
  "data": "room-12345"
}
```

##### POST /api/room/:roomId/leave

**Leave the room**

**Response (200 OK):**

```json
{
  "message": "Successfully left room",
  "data": null
}
```

## 🔗 WebSocket Channels

**Endpoint:** `http://localhost:8080/ws` (STOMP over SockJS)

### Subscribe Channels (Listen for updates)

#### /game/{gameId}

**Subscribe to public game state updates**

**Message Format:**

```json
{
  "gameId": "game-99999",
  "phase": "FLOP",
  "pot": 150,
  "currentBet": 50,
  "communityCards": ["AH", "KD", "QS"],
  "players": [
    {
      "id": "player-1",
      "name": "AlexPoker",
      "chips": 950,
      "status": "ACTIVE",
      "currentBet": 50,
      "hasFolded": false
    }
  ],
  "currentPlayerId": "player-2",
  "currentPlayerName": "BobPoker"
}
```

**Frequency:** On every bet, phase change, or player action

#### /game/{gameId}/player-name/{encodedPlayerName}/private

**Subscribe to private player data (hole cards, final hand)**

**Message Format:**

```json
{
  "playerId": "player-1",
  "holeCards": ["AC", "AS"]
}
```

> **Note:** Only your player receives messages on this channel

#### /rooms{roomId}

**Subscribe to room/lobby updates**

**Message Format:**

```json
{
  "message": "PLAYER_JOINED",
  "data": {
    "roomId": "room-12345",
    "player": "CharliePoker",
    "currentCount": 3
  }
}
```

**Messages:** PLAYER_JOINED, PLAYER_LEFT, ROOM_CLOSED

> Note: some clients may also subscribe to compatibility aliases such as `/rooms/{roomId}` and `/topic/rooms/{roomId}`.

### Send Actions (Player moves)

The client sends player actions via WebSocket to `/app/{gameId}/action`:

```json
{
  "action": "RAISE",
  "amount": 75
}
```

**Valid actions with required fields:**

| Action | Amount | Notes                   |
| ------ | ------ | ----------------------- |
| FOLD   | —      | No chips required       |
| CHECK  | —      | Invalid if a bet exists |
| CALL   | —      | Auto-calculates amount  |
| BET    | ✓      | Initiates betting round |
| RAISE  | ✓      | Must be > current bet   |
| ALL_IN | ✓ or — | All remaining chips     |

## 🔐 Authentication

### JWT Token Flow

```
User                                Backend
  │                                   │
  ├─ POST /api/room/create    ─→      │
  │  (playerName, etc)                │
  │                                   ├─ Validate input
  │                                   ├─ Create room & player
  │                                   ├─ Generate JWT token
  │                                   │  (signed with JWT_SECRET)
  │← {token, roomId} ─────────────────┤
  │                                   │
  ├─ Connect WebSocket     ───→       │
  │  (Authorization: Bearer {token})
  │                                   ├─ WebSocketAuthInterceptor
  │                                   ├─ Extract token from STOMP CONNECT
  │                                   ├─ Validate signature
  │                                   ├─ Set Principal for session
  │← Connected ───────────────────────┤
  │                                   │
  ├─ POST /api/game/{gameId}/action ─→│
  │  (Authorization: Bearer token)    ├─ JwtAuthenticationFilter
  │                                   ├─ Resolve Principal
  │                                   ├─ Execute action
  │← Broadcast update ────────────────┤
```

### Token Configuration

Token settings in `application.properties`:

```properties
jwt.secret=${JWT_SECRET:dev-only-not-for-production-change-me}
jwt.expirationMillis=86400000  # 24 hours
```

> ⚠️ **Important**: The default JWT secret is for **development only**. For production, set the `JWT_SECRET` environment variable to a secure, random value.

**Token Claims:**

```json
{
  "sub": "player-1",
  "iat": 1711353000,
  "exp": 1711439400
}
```

## 🔨 Build & Demo Commands

### Local Build Commands

#### Windows (Maven Wrapper)

```bash
cd Poker/

# Clean & build with tests
.\mvnw.cmd clean package

# Build without tests (faster)
.\mvnw.cmd clean package -DskipTests

# Output: target/Poker-0.0.1-SNAPSHOT.jar
```

#### Mac/Linux (Maven Wrapper)

```bash
cd Poker/

# Clean & build with tests
./mvnw clean package

# Build without tests (faster)
./mvnw clean package -DskipTests

# Output: target/Poker-0.0.1-SNAPSHOT.jar
```

#### Optional: Global Maven (if installed)

```bash
cd Poker/

# Clean & build with tests
mvn clean package

# Build without tests (faster)
mvn clean package -DskipTests
```

> 💡 **Note**: The Maven Wrapper (`mvnw`/`mvnw.cmd`) is recommended as it requires no global Maven installation.

#### Using IDE Maven UI

```bash
# IntelliJ IDEA:
# 1. Right-click pom.xml → Run Maven → clean
# 2. Right-click pom.xml → Run Maven → package

# Eclipse:
# 1. Right-click project → Run As → Maven clean
# 2. Right-click project → Run As → Maven build (with goals: package)
```

### Run the Backend Demo

#### Option 1: Execute JAR directly

```bash
java -jar target/Poker-0.0.1-SNAPSHOT.jar
```

#### Option 2: Run with Maven Wrapper (Windows)

```bash
.\mvnw.cmd spring-boot:run
```

#### Option 3: Run with Maven Wrapper (Mac/Linux)

```bash
./mvnw spring-boot:run
```

#### Option 4: Run with global Maven (if installed)

```bash
mvn spring-boot:run
```

#### Option 5: From IDE

```bash
# IntelliJ/Eclipse/VS Code: Run PokerApplication.java main() method
```

**Server starts on:** `http://localhost:8080`

### Configuration

**application.properties** settings:

```properties
spring.application.name=Poker
server.port=8080

# Logging
logging.level.root=INFO
logging.level.com.pokergame=DEBUG

# JWT
jwt.secret=${JWT_SECRET:dev-only-not-for-production-change-me}
jwt.expirationMillis=86400000

# CORS (localhost + ngrok)
# Configured in SecurityConfig.java
```

> 💡 To customize port, add to `application.properties`: `server.port=9090`

## ✅ Validation Evidence

### Test Suite Overview

Comprehensive test coverage in `src/test/java/com/pokergame/`:

**Unit Tests** (`model/`):

- `CardTest` - Card creation, equality
- `DeckTest` - Deck initialization, shuffle, draw
- `RankTest`, `SuitTest` - Enum validation
- `GameTest` - Game state management
- `GamePhaseTest` - Phase transitions
- `PlayerTest` - Player state updates
- `PlayerActionTest` - Action validation
- `HandEvaluationResultTest` - Hand ranking results
- `HandRankTest` - Hand rank comparisons
- `RoomTest` - Room management

**Integration Tests** (`integration/`):

- `GameLifecycleIntegrationTest` - Full game flow (deal → showdown)
- `RoomLifecycleIntegrationTest` - Room creation, join, leave
- `SecurityIntegrationTest` - JWT auth validation
- `WebSocketSecurityTest` - WebSocket JWT verification

### Execute Tests

```bash
# Windows (recommended: Maven Wrapper)
.\mvnw.cmd test

# Mac/Linux (recommended: Maven Wrapper)
./mvnw test

# Run specific test class (Wrapper)
.\mvnw.cmd test -Dtest=GameLifecycleIntegrationTest
# or: ./mvnw test -Dtest=GameLifecycleIntegrationTest

# Optional: use global Maven only if it is installed and configured
# mvn test
```

### Test Artifacts

Test results are generated in `target/surefire-reports/`:

```bash
cat target/surefire-reports/*.txt  # View test summaries
```

## 🗺️ Future Enhancements & Known Limitations

### Current Status: Work in Progress ⚠️

This project is a **portfolio/demo implementation** of a real-time poker game engine. While the core game logic is solid, some production-readiness features are pending.

## 📌 Portfolio Context

This repository is primarily a showcase artifact rather than a general-purpose library.

Areas I am still working on

- **Performance**: Optimising game state updates, reduce WebSocket message frequency
- **Reliability**: Adding transaction management, consistency checks
- **Testing**: Increasing unit & integration test coverage
- **Security**: Input sanitization, rate limiting, account management

## 📜 License & Attribution

This is a demo/portfolio project. See repository for full licensing details.

---

**Companion frontend showcase:** ([Repo](https://github.com/itzbenjamin17/Poker-Frontend)). Together they demonstrate end-to-end real-time gameplay architecture.
