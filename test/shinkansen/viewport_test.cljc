(ns shinkansen.viewport-test
  "Contract tests for the framework viewport default: a published document
  must carry the device-width viewport meta and lay out for phones."
  (:require [clojure.test :refer [deftest is]]
            [shinkansen.viewport :as vp]))

(def responsive-doc
  (str "<!doctype html><html><head>"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
       "<style>body{margin:0}.layout{display:grid;grid-template-columns:15rem 1fr}"
       "@media(max-width:64rem){.layout{grid-template-columns:1fr}}"
       "@media(max-width:30rem){.layout{font-size:0.9rem}}</style></head><body>ok</body></html>"))

(def desktop-only-doc
  (str "<!doctype html><html><head><style>body{width:1200px}</style></head><body>ok</body></html>"))

(deftest viewport-meta-constant-carries-device-width
  (is (re-find #"width=device-width" vp/viewport-meta))
  (is (re-find #"viewport-fit=cover" vp/viewport-meta)))

(deftest breakpoints-include-a-phone-band
  (is (some #(= :xs (:id %)) (:breakpoints {:breakpoints vp/breakpoints})))
  (is (some #(= 480 (:max-width %)) vp/breakpoints)))

(deftest responsive-document-passes
  (let [r (vp/audit {:file "ok.html" :html responsive-doc})]
    (is (:ok r))
    (is (empty? (:problems r)))
    (is (seq (:bands r)))))

(deftest desktop-only-document-fails-with-named-problems
  (let [r (vp/audit {:file "bad.html" :html desktop-only-doc})]
    (is (not (:ok r)))
    (is (some #(= :viewport-missing (:id %)) (:problems r)))
    (is (some #(= :fixed-width-body (:id %)) (:problems r)))))

(deftest no-xs-band-fails
  ;; a doc with a viewport meta but whose smallest band starts above 480px
  (let [doc (str "<!doctype html><html><head>"
                 "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                 "<style>@media(max-width:64rem){.x{}}</style></head><body></body></html>")
        r (vp/audit {:file "mid.html" :html doc})]
    (is (some #(= :no-xs-band (:id %)) (:problems r)))))
