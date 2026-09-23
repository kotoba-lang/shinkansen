# bench — shinkansen against other Node web frameworks

Same machine, same bytes, same load. `run.cljk` starts each server on port 0,
drives it with autocannon, and refuses a run in which any response carried
the wrong status (a fast wrong answer is not a result).

```bash
npm i --prefix bench                      # pinned: express 5.2.1, fastify 5.12.5,
                                          # hono 4.13.8 + @hono/node-server 2.1.1, autocannon 8.0.0
KOTOBA_LANG=<superproject>/orgs/kotoba-lang \
  kbb --backend sci bench/run.cljk        # ROUNDS=3 DURATION=5 CONNECTIONS=100 WORKERS=4
                                          # ONLY=shinkansen,fastify  OUT=bench/results/<date>.edn
```

Exit 0 only when every median is valid; 1 on any INVALID; 2 when a server
did not start. Output: `RESULT fw scenario rps p99ms cpu-us OK|INVALID`.

## Scenarios

| name | request | what it measures |
|---|---|---|
| `static` | `GET /` | the `:ssg` page (11.6 KB), 200 + ETag |
| `304` | `GET /` + `If-None-Match: <etag>` | revalidation — the common case for a name host (`Cache-Control: no-cache`) |
| `ssr` | `GET /todos/t3` | a page rendered from path params + loaded data |
| `ssr-miss` | `GET /live` | data changes on **every** request, so nothing may be answered from memory: load + render + ETag per request. shinkansen's worst case, on purpose |

## Two numbers, and which one to rank by

- **rps** is what autocannon saw. On a shared machine it moves with everything
  else running (the 2026-09-23 runs had a load average of 60–80 on 10 cores;
  the same server moved ±2× between rounds).
- **cpu-us** is the server's own CPU time per response: user + sys of the
  server's process group from `ps`, read before and after each run, divided by
  responses. It is what the framework charges for one answer and holds up
  under contention far better than rps. **Rank by cpu-us.**

## Why shinkansen can answer cheaper than a JIT-compiled framework

A shinkansen document's ETag *is* the CID of its bytes, so an answered URL is
a value. `shinkansen.host/handle` attaches a plan (`:shinkansen.host/plan`)
saying what may be remembered and on what condition; `shinkansen.serve` keeps
it as a pre-encoded Buffer + flat header array (with `content-length`, so no
chunked framing) in a per-URL table:

- `:ssg` / `:isr` — the answer is a function of the URL: a hit is a Map lookup
  and one write.
- `:ssr` — the load still runs every request; the table answers only when it
  returns a value `=` to the data the bytes were rendered from. Otherwise the
  host renders, hashes and refreshes the entry. A render that reads anything
  but `(params, data)` declares `:memo? false`.
- `304` — decided from the stored ETag without touching the bytes.

The hit path is a small JS closure (`serve/answer-station`), so a hit never
enters the interpreter.

## What is still slower, and why

`ssr-miss` is roughly 10× the CPU per response of the others. On a miss the
app's own render fn and the CID encoding run **interpreted under kbb/sci**,
while every competitor runs as V8-JIT'd JS. The fix there is not in the
framework: an AOT (compiled ClojureScript / Kotoba) build of the app and
`content-address`. That path does not exist for shinkansen yet.

## Competitor fixtures are JS on purpose

`competitors/*.js` are written the way each framework's own docs write them
so the numbers compare frameworks, not our port of them. They are comparison
fixtures, not tooling; nothing in shinkansen depends on them.
