(ns shinkansen.maturity
  "Where shinkansen stands against Next.js / SvelteKit (the meta-framework
  face) and shadcn / Radix (the component face), as DATA with a measured
  column — rewritten 2026-09-16 after the map had gone stale (g0–g3 still
  said 'no route tree yet' while routes.cljc had shipped).

  The distinction this map is for: DECLARED vs DRIVEN. A namespace with a
  contract and tests but no host that calls it is declared; one a running
  host or a product calls is driven. Measured 2026-09-16 over cloud-kotoba /
  net-kotobase / cloud-itonami-app / cloud-kotoba-dds (grep of
  `[shinkansen.<ns>` require forms): 5 namespaces had consumers
  (interaction 10, theme 6, audit 6, viewport 3, coscientist 1) and eleven
  had none. The reference host (shinkansen.host + shinkansen.serve, the
  reference app, host-node-check) is what turns 'declared' into 'driven by
  the framework's own host' — it is NOT a product consumer, and the map
  says which is which.

  Status vocabulary (one of):
    :driven-by-product   a deployed product requires it (count in :consumers)
    :driven-by-host      the reference host runs it end to end (host-node-check)
    :declared            contract + tests, nobody calls it
    :absent              nothing
    :not-by-design       deliberately not built; the reason is in :why
    :unique              the comparison frameworks have no counterpart

  Every :ns named here must exist — maturity-test pins that, so this map
  cannot drift to naming namespaces that were renamed away.")

(def measured-at "2026-09-16")

(def rows
  [{:axis :a :name :routing-layouts
    :theirs "Next: file-system app router, nested/parallel layouts. SvelteKit: file routing, layout groups."
    :ns 'shinkansen.routes :status :driven-by-host :consumers 0
    :have "declared route tree, :param segments, nested layouts composed outside-in by the host, :invocation on the ok result"
    :gap "no product serves through it; app-kotoba-cloud has its own site.cljk"}
   {:axis :b :name :data-loading
    :theirs "Next: RSC async + fetch cache. SvelteKit: load (server/universal), streamed promises."
    :ns 'shinkansen.load :status :driven-by-host :consumers 0
    :have ":build / :edge load, the result is a data-CID station, hydrate bundle rides in the document"
    :gap "no streaming of load results; no product consumer"}
   {:axis :c :name :mutations
    :theirs "Next: server actions. SvelteKit: form actions with use:enhance (works without JS)."
    :ns 'shinkansen.invoke :status :driven-by-host :consumers 0
    :have "one envelope (query/action/event), the authority seam, POST /invoke on the reference host, MCP lake_dispatch as a transport"
    :gap "no no-JS form fallback (a form without the runtime GETs its own page); no product consumer"}
   {:axis :c :name :browser-dispatch-surface
    :theirs "React / Svelte event systems."
    :ns 'shinkansen.interaction :status :driven-by-product :consumers 10
    :have "data-action + data-params, one delegated runtime, streamRun (SSE + NDJSON), hydrate"
    :gap "no client-side reactivity: the browser fills server-rendered mounts, it does not re-render (the re-frame guest exists in shitsuke and is not wired to the DOM — ADR-2609131808 D4)"}
   {:axis :d :name :rendering-modes
    :theirs "Next: SSR / SSG / ISR / PPR / streaming. SvelteKit: SSR / prerender / CSR per route."
    :ns 'shinkansen.render :status :driven-by-host :consumers 0
    :have ":ssg verbatim, :ssr as a query (CID computed after), :isr window recorded and honoured as Cache-Control by the host"
    :gap "no streaming SSR; production deploys are static documents only"}
   {:axis :d :name :client-navigation
    :theirs "Next: Link prefetch, soft navigation. SvelteKit: preload-data, client router."
    :ns nil :status :not-by-design :consumers 0
    :have "none — one document is one CID, a link is a full navigation"
    :why "a soft navigation across CIDs is a navigation across origins on the content host ({cid}.ipfs.*); prefetch on the NAME host is possible and unbuilt"}
   {:axis :e :name :responsive-contract
    :theirs "none in core (Tailwind / CSS)."
    :ns 'shinkansen.viewport :status :driven-by-product :consumers 3
    :have "viewport meta + phone band + fixed-width audit, enforced at publish"
    :gap nil}
   {:axis :e :name :theme
    :theirs "ecosystem (next-themes)."
    :ns 'shinkansen.theme :status :driven-by-product :consumers 6
    :have "light / dark / system, head-script before paint, theme/set framework action"
    :gap "preferences are a NAME-origin property: on {cid}.ipfs.* every document is its own origin (SPEC §1.8)"}
   {:axis :e :name :i18n
    :theirs "Next: middleware-based, DIY. SvelteKit: paraglide et al."
    :ns 'shinkansen.locale :status :declared :consumers 0
    :have "cookie negotiation, host-only cookie attributes, substitute :exact?, format"
    :gap "app-kotoba-cloud cites the contract in docstrings and re-derives it in its own locale.cljk (no require) — SPEC §2.3 forbids exactly that"}
   {:axis :e :name :uiux-fitness-function
    :theirs "none (vitest / playwright ecosystems)."
    :ns 'shinkansen.audit :status :driven-by-product :consumers 6
    :have "21 axes over emitted HTML, every miss named, unmeasured ≠ pass; coscientist kaizen loop"
    :gap nil :unique true}
   {:axis :f :name :dev-experience
    :theirs "dev server + HMR + error overlay (both)."
    :ns 'shinkansen.serve :status :driven-by-host :consumers 0
    :have "node:http dev server, fs.watch rebuild, live-reload stream, a failed rebuild serves its error document as a 500 that names the problem"
    :gap "live-reload, not HMR (there is no module to hot-swap, only a new document CID); no error overlay beyond the error document"}
   {:axis :g :name :deploy-targets
    :theirs "Next: Vercel / standalone. SvelteKit: adapter-auto / node / cloudflare / static."
    :ns 'shinkansen.adapter :status :declared :consumers 0
    :have "adapter shape, receipts = CID set, diff, rollback plan"
    :gap "zero adapters implemented; the two-plane publish is the root script (publish-document.cljk), shinkansen.publish is its front with no consumer"}
   {:axis :g :name :error-handling
    :theirs "Next: error.tsx / not-found. SvelteKit: +error.svelte."
    :ns 'shinkansen.host :status :driven-by-host :consumers 0
    :have "404 / 500 as named error documents that pass the viewport contract; :render-failed / :load-failed named"
    :gap "no per-route error document; the error page is the framework's, not the app's"}
   {:axis :g :name :images-fonts-metadata
    :theirs "Next: next/image, next/font, metadata API. SvelteKit: enhanced-img."
    :ns nil :status :not-by-design :consumers 0
    :have "none; audit :assets-resolve checks that images resolve"
    :why "a :document is one self-contained file (ADR-2609092600) — an optimizer that serves variants from a CDN contradicts the identity rule; metadata is the app's markup"}
   {:axis :h :name :authorization
    :theirs "none in core (Auth.js / Lucia)."
    :ns 'shinkansen.invoke :status :driven-by-host :consumers 0
    :have "DID / CID / grant / effect seam; verified under a real Ed25519 Biscuit + authority lattice (verify-invoke-authority, 16 cases); the reference host binds it"
    :gap "no product binds an authorizer yet; :chain/append / :app/query / :app/assert are not in capability-semantics :kinds"
    :unique true}
   {:axis :h :name :agent-surface
    :theirs "none."
    :ns 'shinkansen.mcp :status :declared :consumers 0
    :have "4 MCP tools; lake_dispatch is an invoke transport (session identity, never an argument)"
    :gap "stdio process still answers R0 (no app attached)" :unique true}
   {:axis :h :name :content-addressing
    :theirs "none."
    :ns 'shinkansen.state :status :driven-by-host :consumers 0
    :have "identity = CID for documents, data, state (chain), route tree; ETag = CID on the name host"
    :gap "chain hash covers the db value only (:prev / :event / :principal are outside it) — SPEC §1.5"
    :unique true}
   ;; ── component face: jp-go-dds + cloud-kotoba-dds vs shadcn / Radix ──
   {:axis :ui :name :components
    :theirs "shadcn ≈ 50 components."
    :ns nil :status :driven-by-product :consumers nil
    :have "jp-go-dds core.cljk 49 (DADS) + cloud-kotoba-dds 18 app patterns (measured 2026-09-16)"
    :gap nil}
   {:axis :ui :name :headless-behaviors
    :theirs "Radix ≈ 28 primitives with focus management, portals, collision-aware positioning."
    :ns nil :status :driven-by-product :consumers nil
    :have "jp-go-dds.behavior 7: dialog / menu / tabs / disclosure / radiogroup / toast / combobox; marker-driven, one runtime; audit :behaviors-delivered"
    :gap "popover / tooltip / hover-card / select / slider / switch / scroll-area / context-menu / navigation-menu / toggle-group / menubar / toolbar; no collision-aware positioning; no portal (data-chrome=float + audit :chrome-layers instead)"}
   {:axis :ui :name :forms
    :theirs "shadcn: react-hook-form + zod, field errors."
    :ns 'shinkansen.actions :status :declared :consumers 0
    :have ":validate fn per declared event"
    :gap "no schema, no field-error markup contract"}
   {:axis :ui :name :data-table
    :theirs "shadcn: tanstack table (sort / filter / virtualize)."
    :ns nil :status :absent :consumers 0
    :have "DADS table markup only" :gap "no behavior"}
   {:axis :ui :name :charts
    :theirs "shadcn: recharts."
    :ns nil :status :absent :consumers 0
    :have "none in the dds" :gap "none"}])

(defn by-status []
  (reduce (fn [m r] (update m (:status r) (fnil inc 0))) {} rows))

(defn declared-only
  "The rows nobody drives — the list a reference host or a product has to
  shorten."
  []
  (filterv #(= :declared (:status %)) rows))
