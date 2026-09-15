(ns shinkansen.actions
  "Declared mutations — the mutations increment (maturity axis C).

  A form POST or an agent dispatch is the SAME thing: an event into the
  state chain (shinkansen.state). This namespace turns declared events
  into a dispatch surface the browser and an MCP agent both use:

    {:events {:cart/add {:validate (fn [event] …)}}}

  → POST /dispatch {event: [::cart/add {:id x}]}
     → validate → state/chain-entry → {:ok true :db-cid … :height …}

  Fail-closed: an undeclared event, or one failing its :validate fn, is
  refused BY NAME before any chain entry is built. The response carries
  the new db CID so the caller (browser or agent) can verify and follow
  the chain — one surface, both clients.

  Pure .cljc. The host owns the HTTP binding and persistence; this owns
  the declaration → validation → chain-entry seam.")

(defn declared?
  "Is this event id in the declaration?"
  [decl event-id]
  (contains? (:events decl) event-id))

(defn validate-event
  "Run the event's :validate fn (default: non-nil event). Returns
  {:ok true} or {:ok false :reason ...} naming the event id."
  [decl event]
  (let [id (first event)
        f (get-in decl [:events id :validate])]
    (cond
      (not (declared? decl id))
      {:ok false :reason :undeclared-event :event-id id}
      (and f (not (f event)))
      {:ok false :reason :validation-failed :event-id id}
      :else {:ok true})))

(defn dispatch
  "Validate then build the chain entry. `state-fn` is the guest's
  re-frame step (db + event → db); `cid-fn` the content-address hash;
  `prev-cid` the current chain head (nil = genesis).
  Returns {:ok true :entry …} or {:ok false :reason …} — the caller
  (browser POST handler, MCP agent) sees the same shape."
  [decl event {:keys [db state-fn cid-fn prev-cid height]}]
  (let [v (validate-event decl event)]
    (if-not (:ok v)
      v
      (let [next-db (state-fn db event)
            text ((resolve 'shinkansen.state/db-text) next-db)
            cid (cid-fn text)
            entry {:db next-db :db-cid cid :prev-cid prev-cid :event event
                   :height (or height 0) :text text}]
        {:ok true :entry entry}))))
