# shinkansen（新幹線）

**shinkansen is the content-addressed web framework for the Kotoba stack: the
view, the state, and the agent-facing tool surface are all addressed by CID.
Nothing in a shinkansen app is located by a mutable name — a link is a CID,
a state transition is a CID, and an agent fetches UI through the same
address a browser does.**

Like its namesake, the framework is fast, straight, and every station is a
stop you can get off at: each document, each db value, each tool result is a
station addressed by content. This is a metaphor name for the
kotoba-lang family and is registered in
`manifest/concept-vocabulary.edn` (com-junkawasaki root).

## What it is — and what it is NOT

shinkansen is a **connection layer over three existing substrates** (ADR-2609131808).
It deliberately does not re-implement UI, state management, or event handling:

| layer | existing substrate shinkansen connects | what shinkansen adds |
|---|---|---|
| view | [`shitsuke/kotoba/*.kotoba`](https://github.com/kotoba-lang/shitsuke) — re-frame/reagent algebra already implemented as amu guests (`reframe_core.kotoba` 304 lines, `reagent_core.kotoba` 150 lines, plus hiccup/hig/tokens/components) | **document → CID publish** (two-plane, via `publish-document.cljk`) |
| state | same `reframe_core.kotoba` — `db + event → db` as a pure guest | **db value → CID chain**: every dispatch's db value is hashed, addressed, and appended |
| tool call | kotoba-server's `word mcp` stdio server shape | **MCP tools** `lake_list` / `lake_head` / `lake_fetch` / `lake_dispatch` so an LLM agent reads the lake and drives the app through tool calls |

js and events stay where they already are: the host's authority. A Kotoba
guest returns an inert document; the host (dom-gpu renderer, browser host,
Worker) renders it and owns every effect. This is the shitsuke boundary
unchanged — shinkansen does not add a DOM capability wire.

## The four planes (ADR-2609092600)

    identity  ipfs://{cid}                       immutable, the only address an app records
    naming    DNSLink / IPNS                     the mutable name over the identity
    bytes     https://{cid}.ipfs.kotobase.net    Location (also {cid}.ipfs.yataverse.com)
    entry     https://{name}.itonami.app/        Location (one DNS label)

A shinkansen link in HTML is `href="https://{cid}.ipfs.kotobase.net/"` — the
CID IS the link. The `:document` kind is self-contained by the ADR's rule:
a document that needs to fetch its own runtime from the CDN is not one file
and cannot claim a CID.

## Repo layout

    src/shinkansen/publish.cljk     view/document → CID (both planes), fail-closed
    src/shinkansen/state.cljk      db value → CID chain (Unison-style content addressing)
    src/shinkansen/mcp.cljk        MCP tool surface (stdio JSON-RPC, kotoba-server shape)
    kotoba/                        .kotoba guests (bridge modules, compiled by amu)
    test/                          kbb -M:test

## Test

```bash
kbb -M:test
```

## Status

R0 scaffold (2026-09-13). First consumer: the yataverse.com lake index
(ADR-2609131630) replaces its hand-written top page with a shinkansen-published
document and exposes the machine API through shinkansen's MCP tools.
