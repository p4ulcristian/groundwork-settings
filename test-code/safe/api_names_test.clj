(ns safe.api-names-test
  "The public names of safe's api. Adding or removing one fails here, so
   the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [safe.api]))

(deftest the-api-names
  (is (= '#{load secret reveal equal? make-secret}
         (set (keys (ns-publics 'safe.api))))))
