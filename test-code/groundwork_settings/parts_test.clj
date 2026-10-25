(ns groundwork-settings.parts-test
  "A place file and its parts: laid side by side into one map, each
   top-level key from exactly one file, every file's sops files read, a
   problem in a part naming the part."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-settings.api :as settings]
   [groundwork-settings.test-files :as files]))

(def ^:private shape
  [:map {:closed true}
   [:server [:map {:closed true} [:port :int]]]
   [:workos [:map {:closed true} [:client-id :string] [:api-key settings/secret]]]
   [:database [:map {:closed true} [:socket :string]]]])

(defn- make-world
  "A folder with a fake sops printing the sandbox's secrets, and a writer
   for the place's and the parts' files in it."
  []
  (let [folder (files/make-folder)
        bin    (files/write-program folder "sops"
                                    "#!/bin/bash\nprintf '%s' '{\"workos\": {\"api-key\": \"sk_sandbox_text\"}}'\n")]
    {:folder   folder
     :sops     (files/write-file folder "parts/workos/sandbox.sops.yaml" "encrypted, says the fake")
     :key-file (files/write-file folder "age-key.txt" "a key, says the fake")
     :env      {"PATH" (str bin ":/usr/bin:/bin")}}))

(defn- write [{:keys [folder]} file-name text]
  (files/write-file folder file-name text))

(defn- workos-part [{:keys [sops key-file] :as world}]
  (write world "parts/workos/sandbox.edn"
         (str "{:secrets [{:from :sops :file \"" sops "\" :age-key-file \"" key-file "\" :timeout-ms 3000}]
 :workos {:client-id \"client_sandbox\" :api-key #secret :workos/api-key}}")))

(defn- database-part [world]
  (write world "parts/database/laptop.edn" "{:secrets [] :database {:socket \"/run/postgresql\"}}"))

(defn- load-place [{:keys [env] :as world} text]
  (settings/load {:file (write world "places/peter.edn" text) :shape shape :env env}))

(defn- problems-of [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:problems (ex-data e)))))

(deftest a-place-is-its-parts-and-its-own-keys
  (let [world    (make-world)
        settings (load-place world (str "{:parts [\"" (workos-part world) "\" \"" (database-part world) "\"]
 :secrets []
 :server {:port 4100}}"))]
    (is (= #{:server :workos :database} (set (keys settings))) ":parts and :secrets are left out")
    (is (= 4100 (get-in settings [:server :port])))
    (is (= "client_sandbox" (get-in settings [:workos :client-id])))
    (is (= "sk_sandbox_text" (settings/reveal (get-in settings [:workos :api-key])))
        "a part's #secret comes from the sops file the part lists")))

(deftest a-key-set-in-two-files-is-refused
  (let [world (make-world)
        part  (database-part world)
        place (write world "places/peter.edn"
                     (str "{:parts [\"" part "\"] :secrets [] :server {:port 4100} :database {:socket \"/tmp\"}}"))]
    (is (= [{:path [:database] :message (str ":database is set in " place " and " part ": a key is set in one file")}]
           (problems-of #(settings/load {:file place :shape shape :env (:env world)}))))))

(deftest a-part-is-checked-as-a-file-of-its-own
  (let [world (make-world)]
    (testing "a part that can't be read"
      (is (= [{:path [:parts 0] :message "the settings file /nowhere/part.edn can't be read"}]
             (problems-of #(load-place world "{:parts [\"/nowhere/part.edn\"] :secrets [] :server {:port 1}}")))))
    (testing "a part that lists parts"
      (let [part (write world "parts/nested.edn" "{:parts [] :secrets []}")]
        (is (= [{:path [:parts 0] :message (str part " lists parts: a part can't")}]
               (problems-of #(load-place world (str "{:parts [\"" part "\"] :secrets [] :server {:port 1}}")))))))
    (testing "a part that says no :secrets"
      (let [part (write world "parts/bare.edn" "{:database {:socket \"/run/postgresql\"}}")]
        (is (= [{:path [:parts 0] :message (str part " says no :secrets: its sops files, or [] for none")}]
               (problems-of #(load-place world (str "{:parts [\"" part "\"] :secrets [] :server {:port 1}}")))))))
    (testing "a part named twice"
      (let [part (database-part world)]
        (is (= [{:path [:parts] :message "names a part twice"}]
               (problems-of #(load-place world (str "{:parts [\"" part "\" \"" part "\"] :secrets [] :server {:port 1}}")))))))))

(deftest a-wrong-value-in-a-part-names-the-part
  (let [world (make-world)
        part  (write world "parts/database/laptop.edn" "{:secrets [] :database {:socket 5432}}")]
    (is (= [{:path [:database :socket] :message (str "should be a string (in " part ")")}]
           (problems-of #(load-place world (str "{:parts [\"" part "\" \"" (workos-part world) "\"] :secrets [] :server {:port 1}}")))))
    (testing "the place's own key names no part"
      (let [problems (problems-of #(load-place world (str "{:parts [\"" (database-part world) "\" \"" (workos-part world) "\"]
 :secrets [] :server {:port \"4100\"}}")))]
        (is (= [{:path [:server :port] :message "should be an integer"}] problems))
        (is (not (str/includes? (pr-str problems) "sk_sandbox_text")))))))
