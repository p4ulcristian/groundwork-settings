(ns safe.api
  "groundwork's settings and secrets: one EDN file per environment, its
   secrets read from the one source the file names, every problem listed
   at once. safe's one public namespace.

     ;; settings/production.edn
     {:secrets {:from         :sops
                :file         \"secrets/production.sops.yaml\"
                :age-key-file \"/run/secrets/age-key\"
                :timeout-ms   30000}
      :server  {:ip \"0.0.0.0\" :port #env [\"PORT\" :int]}
      :postbox {:provider {:name :postmark :server-token #secret :postbox/postmark-token}}}

     (def settings (safe/load {:file  \"settings/production.edn\"
                               :shape settings-shape
                               :env   (System/getenv)}))

     (:postbox settings)
     ;=> {:provider {:name :postmark :server-token #secret :postbox/postmark-token}}

     ;; where it is used, and only there
     (.header request \"X-Postmark-Server-Token\" (safe/reveal server-token))

   A secret is a value of safe's own type: printed, turned into text, in
   ex-data, in malli's errors and written as JSON it says only its name.
   A library's settings shape names it with safe/secret.

   safe logs nothing: it runs before the app's log exists, and returns the
   settings or throws {:type :safe/bad-settings :problems [{:path :message}]}."
  (:refer-clojure :exclude [load])
  (:require
   [clojure.string :as str]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [safe.reading :as reading]
   [safe.secret :as secret]
   [safe.sources :as sources]))

(def secret
  "The malli shape of a secret, for a library's settings shape:
   [:map [:server-token safe/secret]]. Takes only a secret, never a
   string: a token written out is refused."
  secret/secret-shape)

(defn reveal
  "The secret's text, for the one place that uses it (a header, a
   password handed to the driver). Throws :safe/not-a-secret for anything
   else, naming only its class."
  ^String [a-secret]
  (secret/reveal-text a-secret))

(defn equal?
  "Whether a and b, each a secret or a string, hold the same text,
   compared in constant time: for a token a request carries against the
   settings' secret. nil is equal to nothing. A secret's own = is its
   identity, and its hash too: neither looks at the text."
  [a b]
  (secret/equal-texts? a b))

(defn make-secret
  "A secret named secret-name (a namespaced keyword) holding text: for a
   library's tests, and an app that gets a secret some other way."
  [secret-name text]
  (secret/make-secret secret-name text))

;; -----------------------------------------------------------------------------
;; Loading

(def ^:private options-shape
  [:map {:closed true}
   [:file [:string {:min 1}]]
   [:shape some?]
   [:env [:fn {:error/message "should be the environment, (System/getenv), or a map of text to text"}
          #(instance? java.util.Map %)]]])

(defn- check-options [options]
  (when-let [explanation (malli/explain options-shape options)]
    (throw (ex-info (str "safe/load's options have problems: " (pr-str (malli-error/humanize explanation)))
                    {:type :safe/bad-options :errors (malli-error/humanize explanation)})))
  (try (malli/schema (:shape options))
       (catch Exception e
         (throw (ex-info (str "safe/load's :shape is not a malli shape: " (ex-message e))
                         {:type :safe/bad-options :errors {:shape [(ex-message e)]}})))))

(defn- say-problems [file problems]
  (str file " has " (count problems) (if (= 1 (count problems)) " problem:" " problems:")
       (str/join (for [{:keys [path message]} problems]
                   (str "\n  " (pr-str path) " " message)))))

(defn- refuse [file problems]
  (let [sorted (vec (sort-by (comp pr-str :path) problems))]
    (throw (ex-info (say-problems file sorted)
                    {:type :safe/bad-settings :file file :problems sorted}))))

(defn- list-shape-problems
  "The shape's problems, each {:path :message}, but those about a value
   at or under a path already listed: a value that couldn't be read isn't
   wrong twice. An unknown key is listed still: it is the key that's wrong."
  [shape settings skipped-paths]
  (when-let [explanation (malli/explain shape settings)]
    (for [{:keys [in message type]} (:errors (malli-error/with-error-messages explanation))
          :when (or (= ::malli/extra-key type)
                    (not-any? #(= % (take (count %) in)) skipped-paths))]
      {:path (vec in) :message message})))

(defn- check-source
  "The problems of the :secrets source, its paths under :secrets; and
   :token-from must have been an #env in the file: a token never sits in it."
  [raw-source filled-source]
  (concat
   (when (and (map? raw-source) (contains? raw-source :token-from)
              (not (instance? safe.reading.EnvReference (:token-from raw-source))))
     [{:path [:secrets :token-from] :message "should be #env \"NAME\": the token never sits in the file"}])
   (when-let [explanation (malli/explain sources/source-shape filled-source)]
     (for [{:keys [in message]} (:errors (malli-error/with-error-messages explanation))
           :when (not= reading/unfilled (get-in filled-source in))]
       {:path (into [:secrets] in) :message message}))))

(defn- read-secrets
  "[{name secret} problems]: the named source's secrets for the
   references, or the problems of a missing or bad source."
  [raw-settings source references env]
  (let [names (distinct (map :secret-name references))]
    (cond
      (not (contains? raw-settings :secrets))
      [{} [{:path [:secrets] :message "missing: the secrets' source, {:from :sops …} or {:from :1password …}, or nil for none"}]]

      (and (nil? source) (seq names))
      [{} [{:path [:secrets] :message (str "is nil, and the file names secrets: " (str/join " " names))}]]

      (nil? source)
      [{} []]

      :else
      (let [source-problems (check-source (:secrets raw-settings) source)]
        (cond
          (seq source-problems) [{} source-problems]
          ;; an #env of the source's that couldn't be filled: listed already
          (seq (reading/list-unfilled-paths source)) [{} []]
          (empty? names)        [{} []]
          :else
          (let [{:keys [found missing problems]} (sources/read-secrets source env names)]
            [found
             (concat problems
                     (for [{:keys [path secret-name]} references
                           :let [message (get missing secret-name)]
                           :when message]
                       {:path path :message message}))]))))))

(defn load
  "The settings in :file, an EDN map, checked against :shape (a malli
   shape), every key required by it: #env \"NAME\" and #env [\"NAME\" :int]
   filled from :env, (System/getenv) in an app; #secret :ns/name from the
   source the file names under :secrets, a key the result leaves out.
   Throws {:type :safe/bad-settings :file … :problems [{:path … :message …}]}
   with every problem at once: a file that can't be read, a missing or
   unknown key, a wrong type, a missing environment variable, a #secret
   the source doesn't have, a source that fails. A secret is never in a
   problem's message, nor any environment variable's value."
  [{:keys [file shape env] :as options}]
  (check-options options)
  (let [{:keys [settings problem]} (reading/read-file file)]
    (when problem
      (refuse file [problem]))
    (let [[with-env env-problems]           (reading/fill-env settings env)
          [references reference-problems]   (reading/list-secret-references (dissoc with-env :secrets))
          secrets-own-references            (apply concat (reading/list-secret-references (select-keys with-env [:secrets])))
          ;; a #secret in the source is listed once, not read and not checked
          source                            (reading/fill-secrets (:secrets with-env) (constantly nil))
          [found source-problems]           (read-secrets settings source references env)
          filled                            (reading/fill-secrets (dissoc with-env :secrets) found)
          read-problems                     (concat env-problems
                                                    reference-problems
                                                    (for [{:keys [path]} secrets-own-references]
                                                      {:path path :message "the secrets' source can't take a #secret"})
                                                    source-problems)
          skipped                           (concat (reading/list-unfilled-paths filled)
                                                    (map :path read-problems))
          problems                          (concat read-problems
                                                    (list-shape-problems shape filled skipped))]
      (when (seq problems)
        (refuse file problems))
      filled)))
