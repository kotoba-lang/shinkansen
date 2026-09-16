(ns shinkansen.maturity
  "Where shinkansen stands against mainstream meta-frameworks (Next.js,
  SvelteKit, Astro) and what closes each gap. This is an honest maturity
  map, not marketing: each row names the gap, the shinkansen answer that
  ALREADY exists, and the increment that closes it. The structural
  difference is deliberate and stays: identity is content (CID), not a
  mutable route — routing/naming are layers OVER content, never the
  address itself.

  Maturity axes (what SvelteKit/Next give you out of the box):
    A  filesystem routing + layouts        (route tree, shared layout chrome)
    B  data loading + streaming            (load(), Suspense, RSC)
    C  mutations / server actions          (form actions, endpoint handlers)
    D  rendering modes                     (SSR / SSG / ISR / CSR per route)
    E  responsive/UI contract              (viewport, breakpoints, a11y)
    F  dev experience                      (HMR, dev server, error overlays)
    G  deploy targets                      (edge adapters, one env var)
    H  ecosystem                           (plugins, integrations)")

(def g0
  {:name :routing-layouts
    :gap "route tree + nested layouts are filesystem-derived; shinkansen
          has document→CID but no route tree or shared-layout nesting yet."
    :have "site-layout pattern (one shared chrome fn) + locale-agnostic
           entry routes (shinkansen.locale)."
    :next "shinkansen.routes: a declared route tree (path, layout,
           document-cids) — layouts nest by composition, the edge
           resolves path to CID via the tree. No filesystem magic; the tree
           is data, diffable and addressable itself."})
(def g1
  {:name :data-loading
    :gap "no per-document load() phase; the host fetches and inlines by
          hand."
    :have "shinkansen.state (db value → CID chain) + MCP lake tools
          (lake_list/head/fetch) — agents already read app state as
          content."
    :next "shinkansen.load: a declared :load-fn per document that runs at
          publish/build (SSG) or at the edge (SSR), returning a data CID
          the document references. Loading is content-addressed like
          everything else — a load result is a station."})
(def g2
  {:name :mutations
    :gap "no server-action / form-handler surface; dispatch exists only
          through the MCP tool."
    :have "lake_dispatch (state chain event → new db CID) over MCP."
    :next "shinkansen.actions: POST endpoints generated from event
          declarations — a form POST is a dispatch, the response carries
          the new db CID. Same chain, browser-origin callers included."})
(def g3
  {:name :rendering-modes
    :gap "one mode effectively (static documents at publish); no ISR and
          no per-route SSR."
    :have "self-contained :document rule forces static today."
    :next "explicit :mode per document (:ssg default, :ssr via edge fn,
          :isr = ssg + revalidate window recorded on the manifest). The
          manifest already carries per-document metadata — modes are one
          more field with a fail-closed default."})
(def g4
  {:name :responsive-contract
    :gap "was: no viewport/breakpoint enforcement."
    :have "shinkansen.viewport (device-width meta + xs/sm/md/lg bands +
          fail-closed audit) — ENFORCED AT PUBLISH via publish/manifest."
    :next "done (this increment). The audit is static; a real-render
          check (headless viewport screenshot diff) is a later host
          concern, not a framework one."})
(def g5
  {:name :dev-experience
    :gap "no dev server, no HMR, no error overlay."
    :have "pure cljc views + nbb test runner; the host owns effects."
    :next "shinkansen.dev: a file-watching dev server that re-publishes
          to a local gateway and live-reloads the CID — the CID model
          actually helps here (every save is a new station, the browser
          just follows the name)."})
(def g6
  {:name :deploy-targets
    :gap "one path (kotobase gateway planes)."
    :have "bytes-planes config (archive/origin/mirror) on the manifest."
    :next "adapter shape: the manifest gains :target with the plane
          credentials abstracted — a target is a (manifest → receipts)
          fn. Cloudflare/Pages adapters are then thin."})
(def g7
  {:name :ecosystem
    :gap "no plugin story."
    :have "composable namespaces + injected fns everywhere (cid-fn,
          hash-fn, handlers)."
    :next "declare the injection seams as a plugin contract (a plugin =
          fns over the manifest/audit hooks) once two real consumers
          exist — premature now."})

(def gaps
  [g0 g1 g2 g3 g4 g5 g6 g7])

(def done
  "Rows that CLOSED with this increment or earlier — kept so the map
  cannot silently regress to 'everything is missing'."
  [{:axis :e :name :responsive-contract :closed-by "shinkansen.viewport + publish/manifest gate"}
   {:axis :c :name :browser-dispatch-surface
    :closed-by "shinkansen.interaction — data-action / data-params on the control, ONE delegated runtime, the same event ids shinkansen.actions declares; audit :actions-declared measures the gap (2026-09-16)"}
   {:axis :e :name :theme-and-locale-choice
    :closed-by "shinkansen.theme (light/dark/system: kotoba-theme storage, <html data-theme>, head-script before paint) + shinkansen.locale browser side (locale/set writes the negotiation cookie); both are framework actions the runtime answers (2026-09-16)"}])
