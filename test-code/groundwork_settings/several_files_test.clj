(ns groundwork-settings.several-files-test
  "A place that opens several sops files: each secret is read from the one
   file that has it, one in two files or in none is a problem, and a file
   listed twice is refused. A fake sops prints each file's decrypted text,
   chosen by the file it is asked to decrypt."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-settings.api :as settings]
   [groundwork-settings.test-files :as files]))

(def ^:private shape
  [:map {:closed true}
   [:workos [:map {:closed true} [:api-key settings/secret]]]
   [:database [:map {:closed true} [:password settings/secret]]]])

(defn- make-world
  "Two sops files, a vendors' one and a box's own, and a fake sops that
   prints {vendors} for the first and {box} for the second."
  [vendors box]
  (let [folder  (files/make-folder)
        vendors-file (files/write-file folder "dev-vendors.sops.yaml" "encrypted, says the fake")
        box-file     (files/write-file folder "box.sops.yaml" "encrypted, says the fake")
        vendors-json (files/write-file folder "vendors.json" vendors)
        box-json     (files/write-file folder "box.json" box)
        bin     (files/write-program folder "sops"
                                     (str "#!/bin/bash
case \"$4\" in
  '" vendors-file "') cat '" vendors-json "' ;;
  '" box-file "') cat '" box-json "' ;;
  *) echo \"no such file: $4\" >&2; exit 1 ;;
esac
"))]
    {:folder       folder
     :vendors-file vendors-file
     :box-file     box-file
     :key-file     (files/write-file folder "age-key.txt" "a key, says the fake")
     :env          {"PATH" (str bin ":/usr/bin:/bin")}}))

(defn- source [file key-file]
  (str "{:from :sops :file \"" file "\" :age-key-file \"" key-file "\" :timeout-ms 3000}"))

(defn- load-world [{:keys [folder vendors-file box-file key-file env]} files]
  (settings/load {:file  (files/write-file folder "box.edn"
                                           (str "{:secrets [" (str/join " " (map #(source % key-file) (files {:vendors vendors-file :box box-file}))) "]
 :workos   {:api-key  #secret :workos/api-key}
 :database {:password #secret :database/password}}"))
                  :shape shape
                  :env   env}))

(defn- problems-of [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:problems (ex-data e)))))

(deftest each-secret-comes-from-the-file-that-has-it
  (let [world    (make-world "{\"workos\": {\"api-key\": \"sk_vendor\"}}"
                             "{\"database\": {\"password\": \"box-db\"}}")
        settings (load-world world (juxt :vendors :box))]
    (is (= "sk_vendor" (settings/reveal (get-in settings [:workos :api-key]))))
    (is (= "box-db" (settings/reveal (get-in settings [:database :password]))))))

(deftest a-secret-in-two-files-is-refused
  (let [world (make-world "{\"workos\": {\"api-key\": \"sk_vendor\"}, \"database\": {\"password\": \"first-db-text\"}}"
                          "{\"database\": {\"password\": \"second-db-text\"}}")
        problems (problems-of #(load-world world (juxt :vendors :box)))]
    (is (= [{:path    [:database :password]
             :message (str ":database/password is in " (:vendors-file world) " and " (:box-file world)
                           ": a secret lives in one file")}]
           problems))
    (testing "neither value is said"
      (is (not (str/includes? (pr-str problems) "first-db-text")))
      (is (not (str/includes? (pr-str problems) "second-db-text"))))))

(deftest a-secret-in-no-file-names-every-file
  (let [world (make-world "{\"workos\": {\"api-key\": \"sk_vendor\"}}" "{}")]
    (is (= [{:path    [:database :password]
             :message (str ":database/password is in none of " (:vendors-file world) ", " (:box-file world)
                           ": no key password under database")}]
           (problems-of #(load-world world (juxt :vendors :box)))))))

(deftest a-file-listed-twice-is-refused
  (let [world (make-world "{\"workos\": {\"api-key\": \"sk_vendor\"}}" "{}")]
    (is (= [{:path [:secrets] :message "names a file twice"}]
           (problems-of #(load-world world (juxt :vendors :vendors)))))))

(deftest a-file-that-cant-be-read-is-the-only-problem-said
  (testing "what the unread file holds is unknown, so no secret is called missing"
    (let [world (make-world "{\"workos\": {\"api-key\": \"sk_vendor\"}}" "not json")]
      (is (= [{:path [:secrets 1] :message (str "sops's output for " (:box-file world) " is not a JSON object")}]
             (problems-of #(load-world world (juxt :vendors :box))))))))
