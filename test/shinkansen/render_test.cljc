(ns shinkansen.render-test
  "Rendering modes: declared, checked against what the document carries,
  fail-closed on mismatch."
  (:require [clojure.test :refer [deftest is]]
            [shinkansen.render :as render]))

(deftest ssg-is-the-default
  (let [r (render/check-mode {:html "<html>ok</html>"})]
    (is (:ok r))
    (is (= :ssg (:mode r)))))

(deftest unknown-mode-refused
  (let [r (render/check-mode {:mode :csr})]
    (is (not (:ok r)))
    (is (= :unknown-mode (:reason r)))))

(deftest ssr-without-render-fn-refused
  (let [r (render/check-mode {:mode :ssr})]
    (is (not (:ok r)))
    (is (= :ssr-needs-render-fn (:reason r)))))

(deftest ssr-with-frozen-data-cid-refused
  (let [r (render/check-mode {:mode :ssr :render-fn (fn [p d] "") :data-cid "cid-x"})]
    (is (not (:ok r)))
    (is (= :ssr-with-data-cid (:reason r)))))

(deftest ssg-returns-frozen-html
  (let [r (render/render-document {:mode :ssg :html "<html>frozen</html>"})]
    (is (:ok r))
    (is (= "<html>frozen</html>" (:html r)))))

(deftest ssr-calls-render-fn
  (let [r (render/render-document
           {:mode :ssr :render-fn (fn [p d] (str "<html>" (:name d) " for " (:id p) "</html>"))
            :params {:id "abc"} :data {:name "QS"}})]
    (is (:ok r))
    (is (re-find #"QS for abc" (:html r)))))

(deftest ssr-render-failure-is-named
  (let [r (render/render-document
           {:mode :ssr :render-fn (fn [_ _] (throw (ex-info "boom" {})))
            :params {} :data {}})]
    (is (not (:ok r)))
    (is (= :render-failed (:reason r)))
    (is (re-find #"boom" (:error r)))))

(deftest isr-requires-a-revalidate-window
  (is (not (:ok (render/isr-manifest-fields {:mode :isr}))))
  (is (:ok (render/isr-manifest-fields {:mode :isr :revalidate-seconds 60})))
  (is (nil? (render/isr-manifest-fields {:mode :ssg}))))
