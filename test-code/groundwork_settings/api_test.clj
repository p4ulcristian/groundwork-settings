(ns groundwork-settings.api-test
  "Every problem at once, each with its path; #env's types; a secret that
   never shows its text, printed, in an exception, in malli's errors, as
   JSON, in groundwork-logs's lines; reveal, equal? and make-secret."
  (:require
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as json]
   [groundwork-logs.api :as logs]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [groundwork-settings.api :as settings]
   [groundwork-settings.test-files :as files]))

(def ^:private secret-text "pm-text-of-the-postmark-token")

(defn- make-world
  "A folder with a fake sops printing a decrypted tree, and an
   environment whose PATH finds it."
  []
  (let [folder (files/make-folder)
        bin    (files/write-program folder "sops"
                                    (str "#!/bin/bash\nprintf '%s' '{\"groundwork-email\": {\"postmark-token\": \"" secret-text "\"}}'\n"))]
    {:folder    folder
     :sops-file (files/write-file folder "production.sops.yaml" "encrypted, says the fake")
     :key-file  (files/write-file folder "age-key.txt" "a key, says the fake")
     :env       {"PATH" (str bin ":/usr/bin:/bin") "PORT" "8080" "WORKERS" "four" "DEBUG" "yes"}}))

(defn- sops-source [{:keys [sops-file key-file]}]
  (str "{:from :sops :file \"" sops-file "\" :age-key-file \"" key-file "\" :timeout-ms 3000}"))

(def ^:private shape
  [:map {:closed true}
   [:server [:map {:closed true}
             [:ip :string]
             [:port :int]
             [:workers :int]
             [:debug? :boolean]]]
   [:email [:map {:closed true}
              [:server-token settings/secret]
              [:webhook-token settings/secret]]]
   [:errors [:map {:closed true} [:token :string]]]])

(defn- problems-of [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (assoc (ex-data e) :message (ex-message e) :text (str (ex-message e) (pr-str (ex-data e)))))))

(deftest every-problem-is-listed-at-once-with-its-path
  (let [{:keys [folder env] :as world} (make-world)
        file (files/write-file folder "production.edn"
                               (str "{:secrets " (sops-source world) "
 :server  {:ip       80
           :port     #env [\"PORT\" :int]
           :workers  #env [\"WORKERS\" :int]
           :debug?   #env [\"DEBUG\" :boolean]
           :host     #env \"HOST\"}
 :email {:server-token  #secret :groundwork-email/postmark-token
           :webhook-token #secret :groundwork-email/webhook-token}
 :errors  {:token #secret :groundwork-email/postmark-token}
 :meter   {}}"))
        {:keys [type problems message text]}
        (problems-of #(settings/load {:file file :shape shape :env env}))]
    (is (= :groundwork-settings/bad-settings type))
    (is (= [{:path [:errors :token] :message "should be a string"}
            {:path [:meter] :message "disallowed key"}
            {:path [:email :webhook-token] :message (str ":groundwork-email/webhook-token is not in " (:sops-file world) ": no key webhook-token under groundwork-email")}
            {:path [:server :debug?] :message "the environment variable DEBUG is not true or false"}
            {:path [:server :host] :message "the environment variable HOST is not set"}
            {:path [:server :host] :message "disallowed key"}
            {:path [:server :ip] :message "should be a string"}
            {:path [:server :workers] :message "the environment variable WORKERS is not a whole number"}]
           problems))
    (is (str/starts-with? message (str file " has 8 problems:\n  [:errors :token] should be a string\n")))
    (testing "no secret and no environment value is said"
      (is (not (str/includes? text secret-text)))
      (is (not (str/includes? text "four")))
      (is (not (str/includes? text "yes"))))))

(deftest the-file-and-its-secrets-key-are-checked
  (let [{:keys [folder env] :as world} (make-world)
        load-text (fn [text] (problems-of #(settings/load {:file (files/write-file folder "s.edn" text) :shape [:map] :env env})))]
    (is (= [{:path [] :message "the settings file /nowhere.edn can't be read"}]
           (:problems (problems-of #(settings/load {:file "/nowhere.edn" :shape [:map] :env env}))))
        "the file")
    (is (= [{:path [:secrets] :message "missing: the secrets' source, {:from :sops …} or {:from :1password …}, or nil for none"}]
           (:problems (load-text "{:a 1}"))))
    (is (= [{:path [:secrets] :message "is nil, and the file names secrets: :groundwork-email/postmark-token"}]
           (:problems (load-text "{:secrets nil :a #secret :groundwork-email/postmark-token}"))))
    (is (= [{:path [:a] :message "#secret takes a namespaced keyword, #secret :groundwork-email/postmark-token"}
            {:path [:b] :message "#env takes a name, #env \"PORT\", or a name and a type, #env [\"PORT\" :int]; the types are :string, :int and :boolean"}]
           (:problems (load-text "{:secrets nil :a #secret \"postmark-token\" :b #env [\"PORT\" :float]}"))))
    (is (= [{:path [:secrets :file] :message "the secrets' source can't take a #secret"}]
           (:problems (load-text (str/replace (str "{:secrets " (sops-source world) "}")
                                              #":file \"[^\"]*\"" ":file #secret :a/b")))))
    (is (= [{:path [:secrets :from] :message "invalid dispatch value"}]
           (:problems (load-text "{:secrets {:from :vault}}"))))
    (is (str/includes? (:message (first (:problems (load-text "{:secrets nil :a #unknown 1}"))))
                       "No reader function for tag unknown"))
    (is (= [{:path [] :message (str folder "/s.edn has more than one form: it should be one map")}]
           (:problems (load-text "{:secrets nil} {}"))))
    (is (= :groundwork-settings/bad-options (:type (problems-of #(settings/load {:file "x.edn" :shape [:map]})))))))

(deftest env-fills-text-numbers-and-booleans
  (let [{:keys [folder]} (make-world)
        settings (settings/load {:file  (files/write-file folder "s.edn" "{:secrets nil :a #env \"NAME\" :b #env [\"PORT\" :int] :c #env [\"ON\" :boolean] :d #env [\"NAME\" :string] :e [#env \"NAME\"]}")
                             :shape [:map [:a :string] [:b :int] [:c :boolean] [:d :string] [:e [:vector :string]]]
                             :env   {"NAME" "shop" "PORT" "-42" "ON" "false"}})]
    (is (= {:a "shop" :b -42 :c false :d "shop" :e ["shop"]} settings))))

(defn- load-good-settings []
  (let [{:keys [folder env] :as world} (make-world)]
    (settings/load {:file  (files/write-file folder "s.edn" (str "{:secrets " (sops-source world) " :token #secret :groundwork-email/postmark-token}"))
                :shape [:map [:token settings/secret]]
                :env   env})))

(deftest a-secret-never-shows-its-text
  (let [settings (load-good-settings)
        token    (:token settings)
        said     "#secret :groundwork-email/postmark-token"
        never?   #(not (str/includes? (str %) secret-text))]
    (is (= secret-text (settings/reveal token)))
    (testing "printed and turned into text"
      (is (= said (pr-str token) (str token) (print-str token) (format "%s" token) (.toString ^Object token))))
    (is (never? (with-out-str (pprint/pprint settings))))
    (is (never? (binding [*print-dup* true] (pr-str token))))
    (is (never? (binding [*print-meta* true *print-readably* false] (pr-str settings))))
    (testing "in an exception"
      (let [e (ex-info (str "failed with " token) {:settings settings})]
        (is (never? (ex-message e)))
        (is (never? (pr-str (ex-data e))))
        (is (never? (pr-str e)))
        (is (never? (pr-str (Throwable->map e))))))
    (testing "in malli's errors"
      (let [explanation (malli/explain [:map [:token :string]] settings)]
        (is (some? explanation))
        (is (never? (pr-str explanation)))
        (is (never? (pr-str (malli-error/humanize explanation))))
        (is (never? (pr-str (malli-error/humanize explanation {:wrap #(select-keys % [:value :message])}))))))
    (testing "as JSON"
      (is (= (str "{\"token\":\"" said "\"}") (json/write-value-as-string settings))))
    (testing "in groundwork-logs's lines, both formats"
      (doseq [log-format [:json :readable]]
        (let [lines (atom [])
              log   (logs/log-function (logs/make-logger {:lowest-level :debug :format log-format :errors-kept 10
                                                                 :write-to #(swap! lines conj %)}))]
          (log :info :shop/started {:shop/settings settings})
          (log :error :shop/failed {:error (ex-info "no" {:token token})})
          (is (= 2 (count @lines)))
          (is (every? never? @lines))
          (is (every? #(str/includes? % said) @lines)))))
    (testing "a secret where the shape wants text: refused, not said"
      (let [{:keys [folder env] :as world} (make-world)
            {:keys [problems text]}
            (problems-of #(settings/load {:file  (files/write-file folder "s.edn" (str "{:secrets " (sops-source world) " :token #secret :groundwork-email/postmark-token}"))
                                      :shape [:map [:token [:string {:min 100}]]]
                                      :env   env}))]
        (is (= [{:path [:token] :message "should be a string"}] problems))
        (is (never? text))))))

(deftest a-written-out-token-is-refused-where-a-secret-belongs
  (let [{:keys [folder env]} (make-world)
        {:keys [problems text]}
        (problems-of #(settings/load {:file  (files/write-file folder "s.edn" (str "{:secrets nil :token \"" secret-text "\"}"))
                                  :shape [:map [:token settings/secret]]
                                  :env   env}))]
    (is (= [{:path [:token] :message "should be a #secret, read from the settings' secrets source"}] problems))
    (is (not (str/includes? text secret-text)))))

(deftest equal-compares-texts-and-nothing-else
  (let [a  (settings/make-secret :errors/token "same-text")
        a' (settings/make-secret :errors/token "same-text")
        b  (settings/make-secret :errors/token "other-text")]
    (is (settings/equal? a a'))
    (is (settings/equal? a "same-text"))
    (is (settings/equal? "same-text" a))
    (is (not (settings/equal? a b)))
    (is (not (settings/equal? a "same-tex")))
    (is (not (settings/equal? a nil)))
    (is (not (settings/equal? nil nil)))
    (is (not= a a') "= is identity: it never reads the text")
    (is (= a a))
    (is (not= (hash a) (hash a')))))

(deftest reveal-and-make-secret-refuse-what-they-dont-take
  (is (= :groundwork-settings/not-a-secret (:type (ex-data (try (settings/reveal "plain") (catch Exception e e))))))
  (is (= "reveal takes a secret; it was given java.lang.String." (ex-message (try (settings/reveal "plain") (catch Exception e e)))))
  (is (= :groundwork-settings/bad-secret-name (:type (ex-data (try (settings/make-secret :no-namespace "x") (catch Exception e e))))))
  (is (= :groundwork-settings/bad-secret-text (:type (ex-data (try (settings/make-secret :a/b 42) (catch Exception e e)))))))
