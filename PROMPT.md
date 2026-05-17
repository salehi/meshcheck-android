# Kickoff prompt

Paste the text below as the first message in a fresh Claude Code session,
opened in this repository.

---

You are starting work on the **MeshCheck Android contributor app**, a
standalone Android project.

Before doing anything, read these files in order — they are the full context
and you should not need anything outside this repository:

1. `CLAUDE.md` — what MeshCheck is, what this app is, the locked decisions,
   the open decisions, and the platform dependencies.
2. `doc/app-spec.md` — the authoritative app design: UI, the three-state
   model, the foreground-service / background-persistence strategy, QR
   enrollment, the technical stack, and distribution.
3. `doc/agent-protocol.md` — the Node↔Platform protocol contract.
4. `doc/check-types.md` — the check parameter/measurement shapes and outcome
   rules.
5. `proto/agent.proto` — the protocol message schema (vendored; do not edit).

The design is settled; the decisions in `CLAUDE.md` under "Decisions already
locked" are not to be reopened. The items under "Decisions still open" do need
to be made — raise them with me rather than guessing.

This repo currently contains only docs. The first job is to **stand the
project up and begin implementation**. I'd like you to:

1. Confirm you've read the docs and briefly state the build back to me — the
   app's purpose, the connection/background model, and the v1 scope — so we
   know we're aligned.
2. Propose a plan: the Android project scaffold (Gradle, modules, minSdk 21,
   Compose, the dependencies named in the spec), and the order you'd build
   the pieces (suggested: enrollment/QR → credential storage + keypair →
   WebSocket + protocol handshake → check executors → foreground service +
   persistence → the UI screens).
3. Flag the one external blocker early: the QR **enrollment-token redeem**
   endpoint does not exist on the platform yet. Decide with me how to proceed
   (e.g. stub it behind an interface so the rest of the app can be built).

Do not start writing code until we've agreed on the plan.
