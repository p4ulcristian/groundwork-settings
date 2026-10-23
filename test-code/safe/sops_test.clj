(ns safe.sops-test
  "The sops source: against the real sops and age when they are on the
   PATH (a throwaway age key and a file encrypted with it, in a temporary
   folder), and always against a fake sops that prints a fixture of sops's
   decrypted output, for the reading of it."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [safe.api :as safe]
   [safe.sources :as sources]
   [safe.test-files :as files]))

(def ^:private shape
  [:map {:closed true}
   [:postbox [:map {:closed true} [:server-token safe/secret]]]
   [:database [:map {:closed true} [:password safe/secret]]]])

(def ^:private plain-secrets
  "postbox:\n    postmark-token: \"pm \\\"quoted\\\" text, and a newline\\n\"\ncellar:\n    password: db-text-of-the-password\n    port: 5432\n")

(defn- settings-text [sops-file age-key-file timeout-ms password-name]
  (str "{:secrets {:from :sops :file \"" sops-file "\" :age-key-file \"" age-key-file "\" :timeout-ms " timeout-ms "}
 :postbox  {:server-token #secret :postbox/postmark-token}
 :database {:password #secret " password-name "}}"))

(defn- problems-of [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (assoc (ex-data e) :text (str (ex-message e) (pr-str (ex-data e)))))))

(def ^:private system-env
  {"PATH" (files/system-path)})

(defn- run [command]
  (sources/run-program command {"PATH" (files/system-path)} 30000))

(defn- make-encrypted-world
  "A throwaway age key and the plain secrets encrypted to it, by the real
   sops; the plain file is deleted once encrypted."
  []
  (let [folder   (files/make-folder)
        key-file (str (io/file folder "age-key.txt"))
        _        (run ["age-keygen" "-o" key-file])
        public   (second (re-find #"public key: (age1\w+)" (slurp key-file)))
        plain    (files/write-file folder "plain.yaml" plain-secrets)
        {:keys [out]} (run ["sops" "--encrypt" "--age" public plain])
        sops-file (files/write-file folder "production.sops.yaml" out)]
    (io/delete-file plain)
    (.deleteOnExit (io/file key-file))
    {:folder folder :key-file key-file :sops-file sops-file}))

(def ^:private real-sops?
  (and (sources/find-program system-env "sops") (sources/find-program system-env "age-keygen")))

(deftest the-real-sops-decrypts-with-the-named-key
  (if-not real-sops?
    (println "sops or age-keygen is not on the PATH: the real sops is not tested; the fake one is.")
    (let [{:keys [folder key-file sops-file]} (make-encrypted-world)
          load-text #(safe/load {:file  (files/write-file folder "production.edn" %)
                                 :shape shape
                                 :env   system-env})]
      (is (not (str/includes? (slurp sops-file) "db-text-of-the-password")) "the file is encrypted")
      (testing "it resolves"
        (let [settings (load-text (settings-text sops-file key-file 30000 ":cellar/password"))]
          (is (= "pm \"quoted\" text, and a newline\n" (safe/reveal (get-in settings [:postbox :server-token]))))
          (is (= "db-text-of-the-password" (safe/reveal (get-in settings [:database :password]))))
          (is (= "{:postbox {:server-token #secret :postbox/postmark-token}, :database {:password #secret :cellar/password}}"
                 (pr-str settings)))))
      (testing "a name the file doesn't have, and one that isn't text"
        (let [{:keys [problems text]}
              (problems-of #(load-text (str/replace (settings-text sops-file key-file 30000 ":cellar/nope")
                                                    ":postbox/postmark-token" ":cellar/port")))]
          (is (= [{:path [:database :password] :message (str ":cellar/nope is not in " sops-file ": no key nope under cellar")}
                  {:path [:postbox :server-token] :message (str ":cellar/port in " sops-file " is not text: write it quoted")}]
                 problems))
          (is (not (str/includes? text "db-text")))))
      (testing "another key can't decrypt it, and nothing decrypted is said"
        (let [other-key (str (io/file folder "other-key.txt"))
              _         (run ["age-keygen" "-o" other-key])
              {:keys [problems text]}
              (problems-of #(load-text (settings-text sops-file other-key 30000 ":cellar/password")))]
          (is (= [[:secrets]] (map :path problems)))
          (is (str/starts-with? (:message (first problems)) (str "sops could not decrypt " sops-file ": ")))
          (is (not (str/includes? text "db-text")))
          (io/delete-file other-key)))
      (testing "a key file that isn't there"
        (let [{:keys [problems]} (problems-of #(load-text (settings-text sops-file "/nowhere/age-key" 30000 ":cellar/password")))]
          (is (= [{:path [:secrets :age-key-file] :message "the age key file /nowhere/age-key can't be read"}]
                 problems)))))))

(def ^:private decrypted-fixture
  "What `sops --decrypt --output-type json` prints for plain-secrets."
  "{\n\t\"postbox\": {\n\t\t\"postmark-token\": \"pm \\\"quoted\\\" text, and a newline\\n\"\n\t},\n\t\"cellar\": {\n\t\t\"password\": \"db-text-of-the-password\",\n\t\t\"port\": 5432\n\t}\n}\n")

(defn- write-fake-sops
  "A fake sops that checks how it is called and prints out."
  [folder out]
  (let [fixture (files/write-file folder "fixture.json" out)]
    (files/write-program folder "sops"
                         (str "#!/bin/bash
[ -n \"$SOPS_AGE_KEY_FILE\" ] || { echo 'no SOPS_AGE_KEY_FILE' >&2; exit 1; }
[ -z \"$HOME\" ] || { echo 'HOME is set' >&2; exit 1; }
[ \"$1 $2 $3\" = '--decrypt --output-type json' ] || { echo \"called as: $*\" >&2; exit 1; }
cat '" fixture "'
"))))

(deftest a-fake-sops-output-is-read
  (let [folder    (files/make-folder)
        bin       (write-fake-sops folder decrypted-fixture)
        sops-file (files/write-file folder "production.sops.yaml" "encrypted, says the fake")
        key-file  (files/write-file folder "age-key.txt" "a key, says the fake")
        env       {"PATH" (str bin ":/usr/bin:/bin") "HOME" "/home/someone"}
        settings  (safe/load {:file  (files/write-file folder "production.edn" (settings-text sops-file key-file 3000 ":cellar/password"))
                              :shape shape
                              :env   env})]
    (is (= "pm \"quoted\" text, and a newline\n" (safe/reveal (get-in settings [:postbox :server-token]))))
    (is (= "db-text-of-the-password" (safe/reveal (get-in settings [:database :password]))))))

(deftest output-that-isnt-json-is-never-quoted
  (let [folder    (files/make-folder)
        bin       (write-fake-sops folder "{\"cellar\": {\"password\": \"db-text-of-the-password\" BROKEN")
        sops-file (files/write-file folder "production.sops.yaml" "encrypted, says the fake")
        key-file  (files/write-file folder "age-key.txt" "a key, says the fake")
        {:keys [problems text]}
        (problems-of #(safe/load {:file  (files/write-file folder "production.edn" (settings-text sops-file key-file 3000 ":cellar/password"))
                                  :shape shape
                                  :env   {"PATH" (str bin ":/usr/bin:/bin")}}))]
    (is (= [{:path [:secrets] :message (str "sops's output for " sops-file " is not a JSON object")}] problems))
    (is (not (str/includes? text "db-text")))))
