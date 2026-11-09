# 0004: Stateless JWT Session Membership Validation via Nonce

We decided to embed a unique process-local membership UUID into every issued JWT and validate it against the room aggregate's active membership on all secured endpoints.

## Context

JWT tokens were previously validated solely through cryptographic signature verification and expiration timestamps. Because tokens were stateless, if a player left a room and rejoined, or if the server restarted and players re-entered, an old or leaked token remained cryptographically valid. A client presenting a stale token from an earlier session could perform unauthorized actions (such as betting or leaving) or collide with active players.

## Decision

1. **Session Membership Nonce**: Whenever a player creates or joins a room, generate a random UUID `membershipId` stored in `Room.playerMembershipIds`.
2. **Token Claim & User-Destination Binding**: Embed this `membershipId` as a custom claim inside the JWT token and expose it through `PlayerPrincipal`. `PlayerPrincipal.getName()` formats the Spring user-destination identity as `playerName:roomId:membershipId` so a socket retained after leaving a room cannot receive a replacement membership's `/user/queue/private` frames.
3. **Endpoint & Frame Validation**: In `GameLifecycleService.validatePlayerMembership()` and `WebSocketAuthInterceptor`, compare the token's claimed `membershipId` against the room's active `getMembershipId(playerName)`. If mismatched or null, reject immediately.

## Consequences

- Stale or superseded tokens are rejected immediately, preventing session collisions and impersonation.
- The client receives an explicit 401 Unauthorized error that triggers automatic session teardown and re-login.
- Eliminates the need for a server-side token revocation blocklist or database session store.
