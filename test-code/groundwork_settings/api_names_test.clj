(ns groundwork-settings.api-names-test
  "The public names of groundwork-settings's api. Adding or removing one fails here, so
   the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork-settings.api]))

(deftest the-api-names
  (is (= '#{load secret reveal equal? make-secret}
         (set (keys (ns-publics 'groundwork-settings.api))))))
