(ns shinkansen.publish
  "View/document → CID publishing (the identity half of shinkansen).

  The actual two-plane publish is `scripts/publish-document.cljk` in the
  com-junkawasaki root (archive = kotobase.net PUT /ipfs/{cid} → B2;
  origin = R2 ipld/{cid} in kotobase-graph-database-production). This
  namespace is the structured front of that path: it builds the manifest
  the publish script consumes and interprets its verdict, fail-closed.

  A shinkansen document is ONE file (ADR-2609092600 :document rule): the
  view HTML that a shitsuke guest produced, self-contained. A document
  that fetches its own assets from the CDN cannot claim a CID and this
  namespace refuses to manifest one — that refusal is the whole point of
  the :document contract."
  (:require [clojure.string :as str]))

(def ^:const bytes-planes
  "The two Location planes a published document answers on. Identity is
  the CID alone; these are configuration, recorded for the manifest."
  {:archive "https://kotobase.net/ipfs/{cid}"        ; backed by B2, IPNI-advertised
   :origin  "https://{cid}.ipfs.kotobase.net"        ; R2-backed web/bytes plane
   :mirror  "https://{cid}.ipfs.yataverse.com"})     ; the yataverse mirror zone (ADR-2609131630)

(defn- gateway-url?
  "A CID-addressed gateway URL is the content itself, not an external
  asset. The URL forms are the four planes: path-style (`https://
  ipfs.kotobase.net/ipfs/{cid}`) and subdomain-style (`https://{cid}
  .ipfs.kotobase.net/`). The subdomain form does NOT start with the
  gateway host — the CID label comes first — so the check is: host is
  exactly a gateway helper host, or host ENDS WITH one of the
  identity-in-the-label suffixes (.ipfs./.ipns. under kotobase.net /
  yataverse.com / itonami.cloud)."
  [url]
  (let [m (re-matches #"https://([^/]+).*" (str url))
        host (second m)]
    (boolean
     (and host
          (or (#{"ipfs.kotobase.net" "ipfs.yataverse.com" "ipfs.itonami.cloud"
                 "ipns.kotobase.net" "ipns.yataverse.com" "ipns.itonami.cloud"
                 "ipni.kotobase.net" "kotobase.net"} host)
              (re-matches #".*\.(?:ipfs|ipns)\.(?:kotobase\.net|yataverse\.com|itonami\.cloud)" host))))))

(defn manifest
  "Build a publish manifest for one self-contained document file.
  Refuses (nil + reason) a file that references remote assets it would
  fetch at runtime — that breaks the self-contained :document rule.
  Links to the CID-addressed gateway planes are the point of the
  framework, not violations: a CID host link IS the content."
  [{:keys [file html entry-name]}]
  (let [external (->> (re-seq #"(?:src|href)=[\"'](https?://[^\"']+)" (or html ""))
                      (map second)
                      (remove gateway-url?)
                      distinct
                      vec)]
    (if (seq external)
      {:ok false :reason "document is not self-contained: external asset references"
       :external external}
      {:ok true
       :file file
       :entry-name entry-name
       :planes bytes-planes})))

(defn manifest->args
  "The argv for scripts/publish-document.cljk. Kept in one place so the
  caller cannot drift from the script's contract."
  [{:keys [file entry-name]}]
  [file "--id" (or entry-name (str file))])
