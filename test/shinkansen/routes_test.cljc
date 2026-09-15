(ns shinkansen.routes-test
  "Route tree resolution: path → document CID via a declared tree."
  (:require [clojure.test :refer [deftest is]]
            [shinkansen.routes :as routes]))

(def tree
  {:path "/"
   :layout :root-layout
   :document :cid-root
   :children
   [{:path "apps" :layout :apps-layout :document :cid-apps
     :children [{:path ":id" :document :cid-app-detail}]}
    {:path "docs" :document :cid-docs
     :children [{:path "reference" :document :cid-ref
                 :children [{:path "quickstart" :document :cid-qs}]}]}]})

(deftest root-resolves
  (let [r (routes/resolve-path tree "/")]
    (is (:ok r))
    (is (= :cid-root (:document r)))
    (is (= [:root-layout] (:layouts r)))))

(deftest one-level-resolves-with-nested-layout
  (let [r (routes/resolve-path tree "/apps")]
    (is (:ok r))
    (is (= :cid-apps (:document r)))
    (is (= [:root-layout :apps-layout] (:layouts r)))))

(deftest param-segment-captures
  (let [r (routes/resolve-path tree "/apps/abc123")]
    (is (:ok r))
    (is (= :cid-app-detail (:document r)))
    (is (= "abc123" (get-in r [:params :id])))))

(deftest deep-path-resolves
  (let [r (routes/resolve-path tree "/docs/reference/quickstart")]
    (is (:ok r))
    (is (= :cid-qs (:document r)))))

(deftest unmatched-path-fails-closed
  (let [r (routes/resolve-path tree "/nope")]
    (is (not (:ok r)))
    (is (= :no-match (:reason r)))))

(deftest node-without-document-fails-by-name
  ;; /docs has children but if someone removes the :document on "docs",
  ;; a bare /docs request must say :no-document-at-node, not :no-match.
  (let [tree2 (assoc-in tree [:children 1 :document] nil)
        r (routes/resolve-path tree2 "/docs")]
    (is (not (:ok r)))
    (is (= :no-document-at-node (:reason r)))))
