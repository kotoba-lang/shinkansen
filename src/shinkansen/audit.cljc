(ns shinkansen.audit
  "The UI/UX document contract as a deterministic fitness function.

  A shinkansen document is fetched by phones, tablets, desktops, screen
  readers and agents that know nothing about each other. This namespace
  scores ONE emitted document (HTML + inline CSS) against the contract a
  person needs to find their next action on it — and names every miss
  with WHY it matters, so the fix is obvious and the kaizen delta can be
  re-measured. It is the judge of `shinkansen.coscientist`; the loop is
  only as honest as this function, so every axis was seeded from a
  failure measured on a live page (kotoba.cloud/account, 2026-09-15) and
  every axis has a test that shows it both passing and failing.

  Axes (each yields score 0..1, weight, finding-with-why):

    :viewport          device-width meta + a phone band     (shinkansen.viewport)
    :assets-resolve    every same-origin script/stylesheet the document
                       references exists in the published asset set — a
                       document whose script 404s never hydrates, and
                       every 'loading…' cell becomes a permanent lie
    :unique-ids        no duplicate id= — getElementById binds the first
                       only, so every later control with that id is dead
    :nav-one-home      one nav entry per destination, one data-current
    :nav-before-content on the phone band a long nav precedes the content;
                       > 8 nav links before <main> means screens of nav
    :skip-link         a nav before <main> needs a[href=#main]
    :idle-pending      the idle document shows at most ONE pending-state
                       region — twelve 'loading…' cells is fabricated
                       progress, not readiness
    :idle-disabled     a disabled control in the idle document carries no
                       reason a person can act on
    :repeated-actions  the same visible action label 3+ times — one fact,
                       one home
    :plain-labels      headings/labels in task language — no parenthesised
                       protocol lists, no ALL-CAPS field names
    :note-density      explanatory paragraphs on the idle screen
    :locale-path-links hrefs that fork by locale path (/ja/…) — the host
                       negotiates by cookie (shinkansen.locale)
    :fixed-anchor      a position:fixed rule without an inline anchor
                       floats at its static position (a dead gutter)
    :links-resolve     every same-origin <a href> path resolves to a
                       published document or a declared route — the nav
                       that links /docs/ when no /docs/ was emitted is a
                       404 the framework itself wrote (measured 2026-09-15)
    :csp-allows-assets the response's Content-Security-Policy lets the
                       document's own same-origin stylesheet/script/image load —
                       abc2c4f moved CSS to /css/site.css under a CSP of
                       style-src 'unsafe-inline' and every page shipped
                       unstyled while the bytes audited at 100
    :pre-overflow      a document with <pre> needs a rule that lets code
                       blocks scroll (overflow(-x): auto|scroll) — on the
                       phone band they clip mid-line otherwise
    :chrome-layers     chrome the document DECLARES keeps its layer: an
                       element marked data-chrome=top (a console top bar)
                       has a position:sticky|fixed rule with a block
                       anchor, one marked data-chrome=float (a popover
                       menu) has position:absolute|fixed with a z-index —
                       measured 2026-09-15: the top bar scrolled away with
                       the content and the account menu opened IN FLOW,
                       pushing the sidebar apart instead of floating over it

  Fail-closed: an axis that CANNOT be measured (no asset set supplied for
  :assets-resolve) is reported under :unmeasured and excluded from the
  score — it is never a silent pass. A document may carry its own :ctx
  (merged over the shared one) when several hosts share one emit tree —
  the documents/routes a docs-host page can link to are not the apex's. Pure .cljc, no js/, static analysis
  only: it verifies contract MARKERS in the emitted document, not pixels.
  Visibility is structural (hidden= attribute, closed <details>) — what a
  browser hides by default the idle-state axes do not count."
  (:require [clojure.string :as str]
            [shinkansen.viewport :as viewport]))

;; --- a minimal HTML walker -----------------------------------------------
;; Regex over tags is enough here because emitted documents are machine-
;; generated (hiccup → string): attributes are quoted, tags are balanced.
;; The walker tracks ancestry so an axis can ask "is this element visible
;; in the idle document?", "is it inside <nav>?", "does it precede <main>?".

(def ^:private void-tags
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta"
    "param" "source" "track" "wbr"})

(defn- strip-opaque
  "Remove comments, <script> and <style> bodies (kept separately for the
  CSS axes) so their text never counts as visible content."
  [html]
  (-> html
      (str/replace #"(?s)<!--.*?-->" "")
      (str/replace #"(?is)(<script\b[^>]*>).*?</script>" "$1</script>")
      (str/replace #"(?is)(<style\b[^>]*>).*?</style>" "$1</style>")))

(defn- parse-attrs [s]
  (into {}
        (map (fn [[_ k v1 v2 v3]]
               [(str/lower-case k) (or v1 v2 v3 "")]))
        (re-seq #"([a-zA-Z_:][\w:.-]*)(?:\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s\"'>]+)))?" s)))

(defn- decode-entities [s]
  (-> s
      (str/replace "&amp;" "&")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'")
      (str/replace "&nbsp;" " ")))

(defn elements
  "Parse HTML into a vector of element maps in document order:
   {:tag :attrs :text :order :hidden? :in-nav? :in-main? :before-main?}
   :text is the element's full inner text (entities decoded, whitespace
   collapsed). :hidden? is true when the element or an ancestor carries
   hidden= or sits inside a closed <details> (other than its <summary>)."
  [html]
  (let [src (strip-opaque (str html))
        tag-re #"<(/?)([a-zA-Z][a-zA-Z0-9-]*)([^>]*?)(/?)>"
        ;; token stream: [start end kind tag attrs] for tags, text between
        tags (loop [pos 0 acc []]
               (if-let [m (re-find tag-re (subs src pos))]
                 (let [[whole close? tag attrs self?] m
                       i (+ pos (str/index-of (subs src pos) whole))
                       j (+ i (count whole))]
                   (recur j (conj acc {:i i :j j :close? (= close? "/")
                                       :tag (str/lower-case tag)
                                       :attrs (parse-attrs attrs)
                                       :self? (= self? "/")})))
                 acc))]
    (loop [ts tags
           stack []          ; open elements: {:idx :tag :hidden? :nav? :main? :details-closed? :text-start}
           out []
           seen-main? false
           last-end 0
           n 0]
      (if-let [t (first ts)]
        (let [text-before (subs src last-end (:i t))
              ;; attach text to every open element
              stack (mapv #(update % :text str text-before) stack)]
          (cond
            (:close? t)
            (let [k (loop [k (dec (count stack))]
                      (cond (neg? k) nil
                            (= (:tag (nth stack k)) (:tag t)) k
                            :else (recur (dec k))))]
              (if (nil? k)
                (recur (rest ts) stack out seen-main? (:j t) n)
                (let [closed (subvec stack k)
                      finished (mapv (fn [e]
                                       (-> (:el e)
                                           (assoc :text (-> (:text e)
                                                            decode-entities
                                                            (str/replace #"\s+" " ")
                                                            str/trim))))
                                     closed)]
                  (recur (rest ts) (subvec stack 0 k) (into out finished)
                         seen-main? (:j t) n))))

            :else
            (let [parent (peek stack)
                  attrs (:attrs t)
                  tag (:tag t)
                  details-closed? (and (= tag "details") (not (contains? attrs "open")))
                  in-closed-details? (and (:details-closed? parent) (not= tag "summary"))
                  hidden? (boolean (or (contains? attrs "hidden")
                                       (:hidden? parent)
                                       in-closed-details?))
                  el {:tag tag :attrs attrs :order n
                      :hidden? hidden?
                      :in-nav? (boolean (or (= tag "nav") (:nav? parent)))
                      :in-main? (boolean (or (= tag "main") (:main? parent)))
                      :before-main? (and (not seen-main?) (not= tag "main"))}
                  frame {:el el :tag tag :text ""
                         :hidden? hidden?
                         :nav? (:in-nav? el)
                         :main? (:in-main? el)
                         ;; a closed details hides its children except summary;
                         ;; children of that summary are visible, deeper
                         ;; children of other content are hidden.
                         :details-closed? (boolean (or details-closed?
                                                       (and (:details-closed? parent) (not= tag "summary"))))}]
              (if (or (:self? t) (contains? void-tags tag))
                (recur (rest ts) stack (conj out (assoc el :text ""))
                       (or seen-main? (= tag "main")) (:j t) (inc n))
                (recur (rest ts) (conj stack frame) out
                       (or seen-main? (= tag "main")) (:j t) (inc n))))))
        ;; unclosed elements at EOF
        (->> (into out (map (fn [e] (assoc (:el e) :text (str/trim (:text e)))) stack))
             (sort-by :order)
             vec)))))

(defn- style-blocks [html]
  (map second (re-seq #"(?is)<style\b[^>]*>(.*?)</style>" (str html))))

(defn- visible [els] (remove :hidden? els))

(defn- by-tag
  "Thread-last friendly: (->> els (by-tag #{\"a\"}))."
  [tags els]
  (filter #(contains? tags (:tag %)) els))

(defn- button-like
  "Visible action controls: <button> and anchors styled as buttons."
  [els]
  (filter (fn [{:keys [tag attrs]}]
            (or (= tag "button")
                (and (= tag "a") (re-find #"(?i)\bbutton\b" (get attrs "class" "")))))
          els))

;; --- the axes, as data ---------------------------------------------------

(defn normalize-path
  "/docs → /docs/ ; /docs/index.html → /docs/ ; query and fragment dropped."
  [p]
  (let [p (first (str/split (str p) #"[?#]" 2))
        p (if (str/ends-with? p "/index.html") (subs p 0 (- (count p) (count "index.html"))) p)]
    (if (or (str/ends-with? p "/") (re-find #"\.[A-Za-z0-9]+$" p)) p (str p "/"))))

(defn- same-origin-stylesheets [els]
  (->> els
       (keep (fn [{:keys [tag attrs]}]
               (when (and (= tag "link") (re-find #"(?i)stylesheet" (get attrs "rel" "")))
                 (get attrs "href"))))
       (filter #(and (str/starts-with? % "/") (not (str/starts-with? % "//"))))
       (map #(first (str/split % #"[?#]" 2)))
       distinct))

(defn- ratio-score [n allowed step]
  (if (<= n allowed) 1.0 (max 0.0 (- 1.0 (* step (- n allowed))))))

(def axes
  [{:id :viewport :weight 0.10
    :title "Viewport meta + phone band (shinkansen.viewport)"
    :check (fn [{:keys [css-html css-missing file]} _]
             (if (seq css-missing)
               {:unmeasured (str "external stylesheet(s) not supplied: " (str/join ", " css-missing)
                                 " — the media bands live there; pass :css or ctx :stylesheets")}
               (let [r (viewport/audit {:file file :html css-html})
                     ps (:problems r)]
                 (if (:ok r)
                   {:score 1.0}
                   {:score (max 0.0 (- 1.0 (* (/ 1.0 3) (count ps))))
                    :finding (str/join "; " (map #(str (name (:id %)) ": " (:why %)) ps))}))))}

   {:id :assets-resolve :weight 0.14
    :title "Referenced same-origin assets exist in the published set"
    :check (fn [{:keys [els]} {:keys [assets]}]
             (let [refs (->> els
                             (keep (fn [{:keys [tag attrs]}]
                                     (case tag
                                       "script" (get attrs "src")
                                       ;; an image the document shows is part of
                                       ;; its behaviour too (the brand mark, 2026-09-15)
                                       "img" (get attrs "src")
                                       "link" (when (re-find #"(?i)stylesheet|modulepreload|icon"
                                                             (get attrs "rel" ""))
                                                (get attrs "href"))
                                       nil)))
                             (filter #(and (str/starts-with? % "/") (not (str/starts-with? % "//"))))
                             (map #(first (str/split % #"[?#]" 2)))
                             distinct)]
               (cond
                 (empty? refs) {:score 1.0}
                 (nil? assets) {:unmeasured "no asset set supplied — cannot tell whether the referenced scripts exist; a document whose script 404s never hydrates"}
                 :else
                 (let [missing (remove #(contains? assets %) refs)]
                   (if (empty? missing)
                     {:score 1.0}
                     {:score 0.0
                      :finding (str "referenced but absent from the published assets: "
                                    (str/join ", " missing)
                                    " — the document ships, its behaviour does not (every pending cell stays pending forever)")})))))}

   {:id :unique-ids :weight 0.12
    :title "No duplicate id= (getElementById binds the first only)"
    :check (fn [{:keys [els]} _]
             (let [dups (->> els (keep #(get-in % [:attrs "id"])) frequencies
                             (filter (fn [[_ n]] (> n 1))) (sort-by key))]
               (if (empty? dups)
                 {:score 1.0}
                 {:score 0.0
                  :finding (str "duplicate ids: "
                                (str/join ", " (map (fn [[id n]] (str id "×" n)) dups))
                                " — only the first element with each id receives its listener; the others are dead controls")})))}

   {:id :nav-one-home :weight 0.10
    :title "One nav entry per destination, one current item"
    :check (fn [{:keys [els]} _]
             (let [links (->> (visible els) (filter :in-nav?) (by-tag #{"a"}))
                   dup-hrefs (->> links (keep #(get-in % [:attrs "href"])) frequencies
                                  (filter (fn [[_ n]] (> n 1))) (sort-by key))
                   currents (count (filter #(get-in % [:attrs "data-current"]) links))
                   n (+ (count dup-hrefs) (if (> currents 1) (dec currents) 0))]
               (if (zero? n)
                 {:score 1.0}
                 {:score (ratio-score n 0 0.25)
                  :finding (str (when (seq dup-hrefs)
                                  (str "the same destination appears more than once in the nav: "
                                       (str/join ", " (map (fn [[h c]] (str h "×" c)) dup-hrefs))))
                                (when (> currents 1)
                                  (str (when (seq dup-hrefs) "; ") currents " items marked current"))
                                " — one fact, one home: a person cannot tell which entry is the page they are on")})))}

   {:id :nav-before-content :weight 0.10
    :title "Nav links that precede <main> (phone: screens of nav before content)"
    :check (fn [{:keys [els]} _]
             (let [n (count (->> (visible els) (filter :in-nav?) (filter :before-main?) (by-tag #{"a"})))]
               (if (<= n 8)
                 {:score 1.0}
                 {:score (ratio-score n 8 (/ 1.0 12))
                  :finding (str n " nav links precede <main> in document order — on the phone band that is screens of navigation before the first content; collapse the nav into a disclosure below the lg band, or place it after main")})))}

   {:id :skip-link :weight 0.05
    :title "A nav before <main> needs a skip link"
    :check (fn [{:keys [els]} _]
             (let [main-id (some #(when (= "main" (:tag %)) (get-in % [:attrs "id"])) els)
                   nav-first? (some #(and (:in-nav? %) (:before-main? %) (= "a" (:tag %))) els)
                   skip? (some #(and (= "a" (:tag %)) main-id
                                     (= (get-in % [:attrs "href"]) (str "#" main-id))
                                     (:before-main? %))
                               els)]
               (cond
                 (not nav-first?) {:score 1.0}
                 skip? {:score 1.0}
                 :else {:score 0.0
                        :finding "nav precedes <main> but there is no a[href=#main] skip link — keyboard and screen-reader users tab through every nav item on every page"})))}

   {:id :idle-pending :weight 0.12
    :title "At most one pending-state region in the idle document"
    :check (fn [{:keys [els]} _]
             (let [pending (->> (visible els)
                                (filter (fn [{:keys [attrs text]}]
                                          (or (= "loading" (get attrs "data-state"))
                                              (= "pending" (get attrs "data-state"))
                                              (and (contains? attrs "aria-busy")
                                                   (= "true" (get attrs "aria-busy")))))))
                   n (count pending)]
               (cond
                 (<= n 1) {:score 1.0}
                 (<= n 3) {:score 0.5
                           :finding (str n " pending-state cells visible in the idle document — say it once (one role=status region) and show the next action")}
                 :else {:score 0.0
                        :finding (str n " pending-state cells visible in the idle document — that is fabricated progress, not readiness: the person sees a wall of 'loading…' and no action; render one status region + the one next action, reveal the console when its state is known")})))}

   {:id :idle-disabled :weight 0.06
    :title "No disabled control without a reason in the idle document"
    :check (fn [{:keys [els]} _]
             (let [n (count (filter #(contains? (:attrs %) "disabled") (button-like (visible els))))]
               (if (zero? n)
                 {:score 1.0}
                 {:score (ratio-score n 0 0.34)
                  :finding (str n " disabled control(s) visible in the idle document — a greyed button tells the person nothing they can act on; hide it until its state is known, or enable it with the reason beside it")})))}

   {:id :repeated-actions :weight 0.06
    :title "The same visible action label at most twice"
    :check (fn [{:keys [els]} _]
             (let [reps (->> (button-like (visible els)) (map :text) (remove str/blank?) frequencies
                             (filter (fn [[_ n]] (>= n 3))) (sort-by key))]
               (if (empty? reps)
                 {:score 1.0}
                 {:score (if (every? (fn [[_ n]] (<= n 4)) reps) 0.5 0.0)
                  :finding (str "the same action repeated: "
                                (str/join ", " (map (fn [[t n]] (str "\"" t "\"×" n)) reps))
                                " — one page-level action; each fact has one home")})))}

   {:id :plain-labels :weight 0.10
    :title "Headings and labels in task language"
    :check (fn [{:keys [els]} _]
             (let [vis (visible els)
                   headings (->> vis (filter #(#{"h1" "h2" "h3" "summary"} (:tag %))) (map :text) (remove str/blank?))
                   ;; field labels: <label>, <th>, <legend>, or a *__label class
                   field-labels (->> vis
                                     (filter (fn [{:keys [tag attrs]}]
                                               (or (#{"label" "th" "legend"} tag)
                                                   (re-find #"(?i)__label\b" (get attrs "class" "")))))
                                     (map :text) (remove str/blank?))
                   ;; a heading that lists protocols: 本人確認（カード認証 / Stripe Identity）
                   protocol-list (filter #(re-find #"[（(][^）)]*[/／][^）)]*[）)]" %) (concat headings field-labels))
                   ;; a field label shouted in caps: USERNAME / STABLE PRINCIPAL. Headings
                   ;; are exempt — an acronym (NIST CSF 2.0) is task language there.
                   all-caps (filter #(re-find #"^[A-Z][A-Z0-9 _-]{3,}$" %) field-labels)
                   hits (distinct (concat protocol-list all-caps))
                   n (count hits)]
               (if (zero? n)
                 {:score 1.0}
                 {:score (ratio-score n 0 0.2)
                  :finding (str n " label(s) in implementation language: "
                                (str/join " | " (take 6 hits))
                                " — name the person's task; protocol names and field identifiers go under details")})))}

   {:id :note-density :weight 0.05
    :title "Explanatory paragraphs on the idle screen"
    :check (fn [{:keys [els]} _]
             (let [long-ps (->> (visible els) (filter :in-main?) (by-tag #{"p"})
                                (map :text) (filter #(> (count %) 120)))
                   n (count long-ps)]
               (if (<= n 1)
                 {:score 1.0}
                 {:score (ratio-score n 1 0.25)
                  :finding (str n " paragraphs over 120 characters visible in <main> — the idle screen is for the next action; put the explanation under <details> or a linked guide")})))}

   {:id :locale-path-links :weight 0.05
    :title "No locale-forked hrefs (the host negotiates by cookie)"
    :check (fn [{:keys [els]} {:keys [locales] :or {locales #{"ja" "en"}}}]
             (let [hits (->> els (by-tag #{"a"})
                             ;; an explicit language switch (hreflang=) IS the
                             ;; contract's cookie-writing entry — exempt
                             (remove #(contains? (:attrs %) "hreflang"))
                             (keep #(get-in % [:attrs "href"]))
                             (filter (fn [h] (when-let [[_ seg] (re-find #"^/([A-Za-z-]+)(?:/|$)" h)]
                                               (contains? locales seg))))
                             distinct)]
               (if (empty? hits)
                 {:score 1.0}
                 {:score 0.0
                  :finding (str "locale-forked links: " (str/join ", " (take 5 hits))
                                (when (> (count hits) 5) (str " (+" (- (count hits) 5) ")"))
                                " — one locale-free URL per document; the edge negotiates (shinkansen.locale). A forked link is a redirect hop at best and a dead link at worst")})))}

   {:id :fixed-anchor :weight 0.05
    :title "position:fixed rules declare an inline anchor"
    :check (fn [{:keys [css-html css-missing]} _]
             (if (seq css-missing)
               {:unmeasured (str "external stylesheet(s) not supplied: " (str/join ", " css-missing)
                                 " — position rules live there; pass :css or ctx :stylesheets")}
             (let [blocks (mapcat #(re-seq #"([^{}]+)\{([^{}]*)\}" %) (style-blocks css-html))
                   bad (->> blocks
                            (filter (fn [[_ sel decls]]
                                      (and (re-find #"position\s*:\s*fixed" decls)
                                           (not (re-find #"(?:^|;)\s*(?:left|right|inset|inset-inline|inset-inline-start|inset-inline-end)\s*:" decls)))))
                            (map (comp str/trim second)))]
               (if (empty? bad)
                 {:score 1.0}
                 {:score 0.0
                  :finding (str "position:fixed without left/right/inset-inline on: " (str/join ", " (take 3 bad))
                                " — the element floats at its static position (inside the body padding), leaving a dead gutter and pushing the content over twice")}))))}

   {:id :links-resolve :weight 0.12
    :title "Same-origin links resolve to a published document or a declared route"
    :check (fn [{:keys [els]} {:keys [documents routes]}]
             (let [hrefs (->> els (by-tag #{"a"}) (keep #(get-in % [:attrs "href"]))
                              (filter #(and (str/starts-with? % "/") (not (str/starts-with? % "//"))))
                              (map #(first (str/split % #"[?#]" 2)))
                              (remove str/blank?)
                              distinct)]
               (cond
                 (empty? hrefs) {:score 1.0}
                 (and (nil? documents) (nil? routes))
                 {:unmeasured "no :documents / :routes supplied — cannot tell whether the links lead anywhere; a nav that links an unpublished path is a 404 the framework wrote"}
                 :else
                 (let [docs (set (map normalize-path (or documents [])))
                       routes (vec (or routes []))
                       resolves? (fn [h]
                                   (let [n (normalize-path h)]
                                     (or (contains? docs n)
                                         (some (fn [r]
                                                 (cond (string? r) (or (= r h) (= (normalize-path r) n)
                                                                       (and (str/ends-with? r "/") (str/starts-with? h r)))
                                                       :else (boolean (re-find r h))))
                                               routes))))
                       dead (remove resolves? hrefs)]
                   (if (empty? dead)
                     {:score 1.0}
                     ;; binary, like :assets-resolve — a 404 is not a degradation
                     {:score 0.0
                      :finding (str "links to nothing published: " (str/join ", " (take 6 dead))
                                    (when (> (count dead) 6) (str " (+" (- (count dead) 6) ")"))
                                    " — a person who follows them gets the 404 page; emit the document or point the link at one that exists")})))))}

   {:id :csp-allows-assets :weight 0.10
    :title "The response CSP lets the document's own assets load"
    :check (fn [{:keys [els file]} {:keys [csp]}]
             (let [;; a host serves different policies per route: :csp may be
                   ;; (fn [file] csp-or-:none) as well as a value
                   csp (if (fn? csp) (csp file) csp)
                   sheets (same-origin-stylesheets els)
                   same-origin? (fn [u] (and (str/starts-with? u "/") (not (str/starts-with? u "//"))))
                   scripts (->> els (filter #(= "script" (:tag %))) (keep #(get-in % [:attrs "src"])) (filter same-origin?))
                   images (->> els (filter #(= "img" (:tag %))) (keep #(get-in % [:attrs "src"])) (filter same-origin?))
                   directive (fn [name]
                               (some (fn [d] (let [[k & vs] (str/split (str/trim d) #"\s+")]
                                               (when (= k name) (set vs))))
                                     (str/split (str csp) #";")))
                   allows? (fn [name]
                             (let [d (or (directive name) (directive "default-src") #{})]
                               (boolean (or (contains? d "'self'") (contains? d "*")))))]
               (cond
                 (and (empty? sheets) (empty? scripts) (empty? images)) {:score 1.0}
                 (nil? csp) {:unmeasured "no :csp supplied — pass the Content-Security-Policy the host serves with this document, or :none when it serves none; a CSP that omits 'self' blocks the document's own stylesheet silently"}
                 (= csp :none) {:score 1.0}
                 :else
                 (let [blocked (cond-> []
                                 (and (seq sheets) (not (allows? "style-src")))
                                 (conj (str "style-src blocks " (str/join ", " sheets)))
                                 (and (seq scripts) (not (allows? "script-src")))
                                 (conj (str "script-src blocks " (str/join ", " scripts)))
                                 (and (seq images) (not (allows? "img-src")))
                                 (conj (str "img-src blocks " (str/join ", " (take 3 images)))))]
                   (if (empty? blocked)
                     {:score 1.0}
                     {:score 0.0
                      :finding (str (str/join "; " blocked)
                                    " — the browser never requests them: the page ships unstyled / inert while its bytes audit clean")})))))}

   {:id :pre-overflow :weight 0.05
    :title "Code blocks can scroll on the phone band"
    :check (fn [{:keys [els css-html css-missing]} _]
             (let [pres (filter #(= "pre" (:tag %)) els)
                   ;; a rule reaches the block through the pre tag or one of
                   ;; its classes (.kc-docs__config{overflow:auto} counts);
                   ;; an inline style with an overflow-aware white-space
                   ;; (pre-wrap) needs no scrolling at all
                   classes (set (mapcat #(str/split (get-in % [:attrs "class"] "") #"\s+") pres))
                   sel-hits? (fn [sel] (or (re-find #"(?:^|[\s,>])pre\b" sel)
                                           (some #(and (not (str/blank? %)) (re-find (re-pattern (str "\\." % "(?![\\w-])")) sel)) classes)))
                   wraps? (every? #(re-find #"white-space\s*:\s*pre-wrap" (get-in % [:attrs "style"] "")) pres)]
               (cond
                 (empty? pres) {:score 1.0}
                 wraps? {:score 1.0}
                 (seq css-missing) {:unmeasured (str "external stylesheet(s) not supplied: " (str/join ", " css-missing) " — the pre overflow rule lives there")}
                 (some (fn [css]
                         (some (fn [[_ sel decls]]
                                 (and (sel-hits? sel)
                                      (or (re-find #"overflow(?:-x)?\s*:\s*(?:auto|scroll)" decls)
                                          (re-find #"white-space\s*:\s*pre-wrap" decls))))
                               (re-seq #"([^{}]+)\{([^{}]*)\}" css)))
                       (style-blocks css-html))
                 {:score 1.0}
                 :else {:score 0.0
                        :finding (str (count pres) " <pre> block(s) and no overflow-x:auto (or pre-wrap) rule reaching them — on a 390px phone the code clips mid-line and cannot be scrolled")})))}

   {:id :chrome-layers :weight 0.08
    :title "Declared chrome keeps its layer: a top bar sticks, a menu floats"
    :check (fn [{:keys [els css-html css-missing]} _]
             ;; hidden elements count here on purpose: a menu panel lives
             ;; inside a closed <details> / behind hidden= until it opens,
             ;; and its layer rule must exist BEFORE it does
             (let [marked (fn [v] (filter #(= v (get-in % [:attrs "data-chrome"])) els))
                   tops (marked "top") floats (marked "float")]
               (cond
                 (and (empty? tops) (empty? floats)) {:score 1.0}
                 (seq css-missing)
                 {:unmeasured (str "external stylesheet(s) not supplied: " (str/join ", " css-missing)
                                   " — the chrome's position rules live there; pass :css or ctx :stylesheets")}
                 :else
                 (let [blocks (mapcat #(re-seq #"([^{}]+)\{([^{}]*)\}" %) (style-blocks css-html))
                       rule? (fn [marker pred]
                               (some (fn [[_ sel decls]]
                                       (and (re-find (re-pattern (str "\\[data-chrome=\"?" marker "\"?\\]")) sel)
                                            (pred decls)))
                                     blocks))
                       top-ok? (rule? "top" #(and (re-find #"position\s*:\s*(?:sticky|fixed)" %)
                                                  (re-find #"(?:^|;)\s*(?:top|inset-block-start|inset-block|inset)\s*:" %)))
                       float-ok? (rule? "float" #(and (re-find #"position\s*:\s*(?:absolute|fixed)" %)
                                                      (re-find #"(?:^|;)\s*z-index\s*:" %)))
                       bad (cond-> []
                             (and (seq tops) (not top-ok?))
                             (conj "data-chrome=top has no position:sticky|fixed + top rule addressed to it — the bar scrolls away with the content")
                             (and (seq floats) (not float-ok?))
                             (conj "data-chrome=float has no position:absolute|fixed + z-index rule addressed to it — the menu opens in flow and pushes the layout apart instead of floating over it"))]
                   (if (empty? bad)
                     {:score 1.0}
                     {:score 0.0 :finding (str/join "; " bad)})))))}])

;; --- scoring --------------------------------------------------------------

(defn score-document
  "Score one emitted document {:file :html :css?}. `ctx` may carry
   :assets (set of same-origin paths that exist in the published set,
   e.g. #{\"/js/session.js\"}), :stylesheets (map of same-origin href →
   CSS text, so a <link rel=stylesheet href=\"/css/site.css\"> resolves) and
   :locales (set of locale path segments). Returns
   {:file :overall 0..100 :axes [...] :findings [...] :unmeasured [...]}.
   Overall is the weighted mean over MEASURED axes only; unmeasured axes are
   listed, never scored. A referenced same-origin stylesheet that neither
   :css nor :stylesheets supplies makes the CSS-dependent axes unmeasured —
   the score-site lesson from 90-docs/design-quality iteration 03: scoring
   the HTML shell alone undercounts silently."
  [{:keys [html file css] :as doc} ctx]
  (let [els (elements html)
        refs (same-origin-stylesheets els)
        resolved (keep #(get-in ctx [:stylesheets %]) refs)
        css-missing (vec (remove #(contains? (:stylesheets ctx {}) %) refs))
        css-text (str/join "\n" (concat (cond (nil? css) [] (string? css) [css] :else css)
                                        resolved))
        doc (assoc doc
                   :els els
                   :css-missing css-missing
                   :css-html (str html (when (seq css-text) (str "<style>" css-text "</style>"))))
        results (mapv (fn [{:keys [id title weight check]}]
                        (let [r (check doc ctx)]
                          (merge {:id id :title title :weight weight} r)))
                      axes)
        measured (remove :unmeasured results)
        total (reduce + (map :weight measured))
        weighted (reduce + (map #(* (:score %) (:weight %)) measured))]
    {:file file
     :overall (if (pos? total) (* 100.0 (/ weighted total)) 0.0)
     :axes results
     :findings (vec (keep (fn [{:keys [id finding score weight]}]
                            (when finding {:axis id :score score :weight weight :finding finding}))
                          measured))
     :unmeasured (vec (keep (fn [{:keys [id unmeasured]}]
                              (when unmeasured {:axis id :why unmeasured}))
                            results))}))

(defn audit
  "Audit many documents: `docs` is a seq of {:file :html} (+ an optional
   per-document :ctx merged over the shared ctx — a docs-host page links
   against its own document set). Returns
   {:overall mean :documents {file -> report} :findings [...] :unmeasured [...]}
   with findings aggregated per axis and sorted by recoverable headroom
   (weight × summed shortfall), heaviest first — the hypothesis seed list
   for shinkansen.coscientist. An audit of zero documents is :overall 0.0
   with :empty? true — never a clean pass."
  ([docs] (audit docs {}))
  ([docs ctx]
   (let [reports (into {} (map (fn [d] [(:file d) (score-document d (merge ctx (:ctx d)))]) docs))
         n (count reports)
         overall (if (zero? n) 0.0 (/ (reduce + (map :overall (vals reports))) n))
         findings (->> axes
                       (keep (fn [{:keys [id weight]}]
                               (let [hits (keep (fn [[f rep]]
                                                  (let [a (first (filter #(= (:id %) id) (:axes rep)))]
                                                    (when (:finding a) [f (:finding a) (:score a)])))
                                                reports)]
                                 (when (seq hits)
                                   {:axis id :weight weight
                                    :files (mapv first hits)
                                    :finding (second (first hits))
                                    :worst-score (reduce min (map #(nth % 2) hits))
                                    :headroom (* weight (- (count hits) (reduce + (map #(nth % 2) hits))))}))))
                       (sort-by :headroom >)
                       vec)
         unmeasured (->> reports
                         (mapcat (fn [[f rep]] (map #(assoc % :file f) (:unmeasured rep))))
                         vec)]
     {:overall overall
      :empty? (zero? n)
      :documents reports
      :findings findings
      :unmeasured unmeasured})))
