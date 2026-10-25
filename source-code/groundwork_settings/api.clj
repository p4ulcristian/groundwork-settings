(ns groundwork-settings.api
  "groundwork's settings and secrets: one EDN file per place the app
   runs, its secrets read from the sops files it lists (each secret in
   exactly one), every problem listed at once. groundwork-settings's one
   public namespace.

     ;; settings/production.edn
     {:secrets [{:from         :sops
                 :file         \"secrets/production.sops.yaml\"
                 :age-key-file \"/run/secrets/age-key\"
                 :timeout-ms   30000}]
      :server  {:ip \"0.0.0.0\" :port #env [\"PORT\" :int]}
      :email {:provider {:name :postmark :server-token #secret :groundwork-email/postmark-token}}}

     (def settings (settings/load {:file  \"settings/production.edn\"
                               :shape settings-shape
                               :env   (System/getenv)}))

     (:email settings)
     ;=> {:provider {:name :postmark :server-token #secret :groundwork-email/postmark-token}}

     ;; where it is used, and only there
     (.header request \"X-Postmark-Server-Token\" (settings/reveal server-token))

   A secret is a value of groundwork-settings's own type: printed, turned into text, in
   ex-data, in malli's errors and written as JSON it says only its name.
   A library's settings shape names it with settings/secret.

   groundwork-settings logs nothing: it runs before the app's log exists, and returns the
   settings or throws {:type :groundwork-settings/bad-settings :problems [{:path :message}]}."
  (:refer-clojure :exclude [load])
  (:require
   [clojure.string :as str]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [groundwork-settings.parts :as parts]
   [groundwork-settings.reading :as reading]
   [groundwork-settings.secret :as secret]
   [groundwork-settings.sources :as sources]))

(def secret
  "The malli shape of a secret, for a library's settings shape:
   [:map [:server-token settings/secret]]. Takes only a secret, never a
   string: a token written out is refused."
  secret/secret-shape)

(defn reveal
  "The secret's text, for the one place that uses it (a header, a
   password handed to the driver). Throws :groundwork-settings/not-a-secret for anything
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
    (throw (ex-info (str "settings/load's options have problems: " (pr-str (malli-error/humanize explanation)))
                    {:type :groundwork-settings/bad-options :errors (malli-error/humanize explanation)})))
  (try (malli/schema (:shape options))
       (catch Exception e
         (throw (ex-info (str "settings/load's :shape is not a malli shape: " (ex-message e))
                         {:type :groundwork-settings/bad-options :errors {:shape [(ex-message e)]}})))))

(defn- say-problems [file problems]
  (str file " has " (count problems) (if (= 1 (count problems)) " problem:" " problems:")
       (str/join (for [{:keys [path message]} problems]
                   (str "\n  " (pr-str path) " " message)))))

(defn- refuse [file problems]
  (let [sorted (vec (sort-by (comp pr-str :path) problems))]
    (throw (ex-info (say-problems file sorted)
                    {:type :groundwork-settings/bad-settings :file file :problems sorted}))))

(defn- say-origin
  "The problem, its message naming the part its key came from (a key of
   the place file itself names none: the file is said at the top)."
  [origins {:keys [path] :as problem}]
  (if-let [part (and (seq path) (get origins (first path)))]
    (update problem :message str " (in " part ")")
    problem))

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

(defn- check-sources
  "The problems of :secrets, the sops files, their paths under :secrets."
  [filled-source]
  (when-let [explanation (malli/explain sources/sources-shape filled-source)]
    (for [{:keys [in message]} (:errors (malli-error/with-error-messages explanation))
          :when (not= reading/unfilled (get-in filled-source in))]
      {:path (into [:secrets] in) :message message})))

(defn- read-secrets
  "[{name secret} problems]: the listed sops files' secrets for the
   references, or the problems of missing or bad :secrets."
  [raw-settings source references env]
  (let [names (distinct (map :secret-name references))]
    (cond
      (not (contains? raw-settings :secrets))
      [{} [{:path [:secrets] :message "missing: the sops files, [{:from :sops …} …], or [] for none"}]]

      :else
      (let [source-problems (check-sources source)]
        (cond
          (seq source-problems) [{} source-problems]
          ;; an #env of the source's that couldn't be filled: listed already
          (seq (reading/list-unfilled-paths source)) [{} []]
          (empty? names)        [{} []]
          (empty? source)
          [{} [{:path [:secrets] :message (str "lists no sops file, and the file names secrets: " (str/join " " names))}]]
          :else
          (let [{:keys [found missing problems]} (sources/read-secrets source env names)]
            [found
             (concat problems
                     (for [{:keys [path secret-name]} references
                           :let [message (get missing secret-name)]
                           :when message]
                       {:path path :message message}))]))))))

(defn load
  "The settings of the place file :file, an EDN map, with its :parts
   (groundwork-settings.parts: each top-level key set in exactly one file),
   checked against :shape (a malli shape), every key required by it:
   #env \"NAME\" and #env [\"NAME\" :int] filled from :env, (System/getenv)
   in an app; #secret :ns/name from the sops files every file lists under
   :secrets. :parts and :secrets are left out of the result.
   Throws {:type :groundwork-settings/bad-settings :file … :problems [{:path … :message …}]}
   with every problem at once: a file that can't be read, a missing or
   unknown key, a wrong type, a missing environment variable, a #secret
   the source doesn't have, a source that fails. A secret is never in a
   problem's message, nor any environment variable's value."
  [{:keys [file shape env] :as options}]
  (check-options options)
  (let [{place :settings problem :problem} (reading/read-file file)
        _                                 (when problem (refuse file [problem]))
        {:keys [settings origins] gather-problems :problems} (parts/gather file place)]
    (when (seq gather-problems)
      (refuse file gather-problems))
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
                                                      {:path path :message "the sops files can't take a #secret"})
                                                    source-problems)
          skipped                           (concat (reading/list-unfilled-paths filled)
                                                    (map :path read-problems))
          problems                          (map (partial say-origin origins)
                                                 (concat read-problems
                                                         (list-shape-problems shape filled skipped)))]
      (when (seq problems)
        (refuse file problems))
      filled)))
