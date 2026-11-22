# 0004: Stateless JWT Session Membership Validation via Nonce (Number only used once)

We decided to generate a unique `membershipId` pass whenever a player joins a room, embed it in their JWT token, and check it against the `Room`'s current member list on every secured HTTP request and WebSocket message.

## Context

Previously, JWT tokens were checked only for a valid cryptographic signature and expiration time. Because JWTs are stateless, if a player left a room and joined again—or if someone reused an old token after leaving—the old token was still cryptographically valid. A client holding a stale token from an earlier visit could still send bets, leave requests, or receive private messages meant for the new session.

## Decision

1. **Per-Join Membership Pass**: Every time a player creates or joins a room, `Room` generates a fresh random UUID (`membershipId`) and stores it in `Room.playerMembershipIds`.
2. **Token Claim & Private Message Routing**: Embed `membershipId` inside the player's JWT token and expose it on `PlayerPrincipal`. `PlayerPrincipal.getName()` formats the user's WebSocket routing identity as `playerName:roomId:membershipId` so an old WebSocket connection left open after leaving a room can never receive a newer session's `/user/queue/private` hole-card messages.
3. **Request & Frame Check**: In `GameLifecycleService.validatePlayerMembership()` and `WebSocketAuthInterceptor`, compare the JWT's `membershipId` against `room.getMembershipId(playerName)`. If the player is no longer in the room or the `membershipId` does not match, reject the request immediately.

## Consequences

- Old or replaced tokens stop working the instant a player leaves or rejoins a room, preventing session mix-ups and impersonation.
- Rejected clients receive a `401 Unauthorized` response, which prompts the frontend to clear its stale session and return to the lobby.
- No database table or server-side token blocklist is needed to invalidate old tokens.