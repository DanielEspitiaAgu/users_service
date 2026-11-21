# Internal result registration contract

`POST /users/{externalId}/results` is internal-only and continues to require the
existing `X-Internal-Token`. Godot must never call it. `externalId` comes from the
internal route; it is not accepted in the JSON body.

`GET /users/{externalId}/solved-cases` is also internal-only and requires the
same token. It returns a JSON array of distinct case IDs for which that user has
a persisted winning result in any mode. `partidas-service` uses this history to
choose VERSUS cases that neither participant has already solved.

## Request

Every new terminal result requires `matchId`, `caseId`, `mode`, `won`, and
`timeSeconds`. `mode` must be `CLASSIC`, `TIMED`, or `VERSUS`; `matchId` and
`caseId` must be nonblank and at most 255 characters; time must be nonnegative.

For `CLASSIC`, the internal caller supplies `difficulty` (`EASY`, `MEDIUM`, or
`HARD`) obtained from trusted case data. `users_service` validates the value and
calculates the award itself: 50, 100, or 150 XP for the first win only. `xpDelta`
must be omitted or null for `CLASSIC`.

For `TIMED` and `VERSUS`, the internal caller continues to supply `xpDelta` using
the existing backend formulas. This endpoint does not trust the game client for
that value: the request must pass the existing internal-token guard. The service
requires an `xpDelta` for these modes but does not independently verify the
formula. It does validate the mode, IDs, time, and result fields.

The internal token authenticates the calling service; it does not prove that a
payload's `won` or `difficulty` is true. The caller remains responsible for
validating the solution and sourcing difficulty from the case service. This
service validates the allowed difficulty vocabulary, but does not make a network
call to re-fetch case metadata.

Example classic request:

```json
{
  "matchId": "server-generated-match-id",
  "caseId": "case-id",
  "mode": "CLASSIC",
  "won": true,
  "timeSeconds": 420,
  "difficulty": "MEDIUM",
  "xpDelta": null
}
```

Example timed or versus request retains the backend-computed XP:

```json
{
  "matchId": "server-generated-match-id",
  "caseId": "case-id",
  "mode": "TIMED",
  "won": true,
  "timeSeconds": 420,
  "difficulty": null,
  "xpDelta": 75
}
```

## Idempotency and response

The unique key is `(user_id, match_id)`. Repeating the same request returns the
persisted `matchId`, `caseId`, `mode`, `won`, applied `xpDelta`, and
`firstWinRewarded`; aggregate stats are current and `newAchievements` is empty on
a replay. Changing the case, mode, outcome, time, difficulty, or non-classic
`xpDelta` for an existing `matchId` returns HTTP 409 without a second write.

An incorrect in-game attempt is not a terminal result and must not be sent to this
endpoint. A terminal classic defeat is stored with zero XP but does not create a
claim in `classic_case_rewards`.

## Compatibility rollout

The current `partidas-service` CLASSIC caller passes the persisted match ID and
the difficulty retrieved from `cases-service`; TIMED/VERSUS pass their persisted
match ID and omit difficulty. These payloads match this contract. Do not create
synthetic match IDs to conceal incompatibilities: doing so would lose retry
idempotency. TIMED/VERSUS `xpDelta` remains accepted from the authenticated
internal service so their current formulas can be preserved.
