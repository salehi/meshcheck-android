# android-app — handoff bundle

This directory is a **self-contained handoff bundle** for starting the
MeshCheck Android contributor app as a separate project. It was assembled from
the MeshCheck platform monorepo so a fresh session, with no access to that
monorepo and no prior context, can pick the app up and run with it.

## How to use it

1. **Move this directory out** of the MeshCheck monorepo to wherever the new
   Android project will live, and rename it as you like (e.g. `meshcheck-android`).
2. `git init` it as its own repository.
3. Open a fresh Claude Code session in it.
4. Paste the prompt from `PROMPT.md` as the first message.

The new session reads `CLAUDE.md` and `doc/` and starts warm — it has the
product context, the locked decisions, the protocol, and the check specs
without re-deriving anything.

## What's in here

| File | What it is | Origin |
|---|---|---|
| `CLAUDE.md` | Durable project context for the new repo: what MeshCheck is, what the app is, locked vs. open decisions, platform dependencies, the proto-sync rule. | Written for this bundle. |
| `PROMPT.md` | The kickoff prompt to paste into the fresh session. | Written for this bundle. |
| `doc/app-spec.md` | The authoritative app design. | The monorepo's `doc/decisions/phases/phase-6-android-app.md` (cross-links adjusted). |
| `doc/agent-protocol.md` | The Node↔Platform protocol contract. | Verbatim copy of the monorepo's `doc/protocols/agent-protocol.md`. |
| `doc/check-types.md` | Check parameter/measurement JSON shapes and outcome rules. | Transcribed from the monorepo's Go `internal/checkspec`. |
| `proto/agent.proto` | The protocol message schema. | Verbatim copy of the monorepo's `proto/agent.proto`. |

## Keeping in sync with the platform

`proto/agent.proto` and `doc/agent-protocol.md` are **snapshots** of the
platform monorepo. They are vendored at a pinned version. When the platform
revs the protocol, re-copy both files deliberately and regenerate the app's
message types. See `CLAUDE.md` § "The protocol coupling".

`doc/check-types.md` is a hand transcription of the platform's `checkspec`
package — if the platform adds or changes a check type, update it too.
