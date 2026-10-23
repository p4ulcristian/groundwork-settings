(ns groundwork-settings.reading
  "Reading a settings file: one EDN map, with #secret and #env tags,
   each read as a reference and filled in afterwards, walking the map
   with every value's path."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io PushbackReader)))

(defrecord SecretReference [given])
(defrecord EnvReference [given])

(def unfilled
  "Put where a reference couldn't be filled; its problem is already
   listed, and the shape's problems at that path are dropped."
  ::unfilled)

(def readers
  {'secret ->SecretReference
   'env    ->EnvReference})

(defn- problem [path message]
  {:path path :message message})

(defn read-file
  "{:settings map} or {:problem …}: the file's one EDN form, which must
   be a map. Tags other than #secret, #env and EDN's own refuse it."
  [file]
  (let [f (io/file file)]
    (if-not (.canRead f)
      {:problem (problem [] (str "the settings file " file " can't be read"))}
      (try
        (with-open [reader (PushbackReader. (io/reader f))]
          (let [end      (Object.)
                settings (edn/read {:readers readers :eof end} reader)
                more     (edn/read {:readers readers :eof end} reader)]
            (cond
              (identical? settings end)   {:problem (problem [] (str file " is empty: it should be one map"))}
              (not (identical? more end)) {:problem (problem [] (str file " has more than one form: it should be one map"))}
              (not (map? settings))       {:problem (problem [] (str file " should be one map"))}
              :else                       {:settings settings})))
        (catch Exception e
          ;; EDN's reading errors say where and what, never a value's text
          {:problem (problem [] (str file " is not readable EDN: " (ex-message e)))})))))

(defn walk-with-paths
  "value with every reference (SecretReference or EnvReference) replaced
   by (replace reference path), walking maps, vectors, lists and sets."
  [value path replace]
  (cond
    (or (instance? SecretReference value) (instance? EnvReference value))
    (replace value path)

    (map? value)
    (into (empty value) (map (fn [[k v]] [k (walk-with-paths v (conj path k) replace)])) value)

    (vector? value)
    (into [] (map-indexed (fn [i v] (walk-with-paths v (conj path i) replace))) value)

    (set? value)
    (into (empty value) (map-indexed (fn [i v] (walk-with-paths v (conj path i) replace))) value)

    (seq? value)
    (apply list (map-indexed (fn [i v] (walk-with-paths v (conj path i) replace)) value))

    :else value))

;; -----------------------------------------------------------------------------
;; #env

(def env-types
  "What #env [\"NAME\" type] turns the text into."
  #{:string :int :boolean})

(defn- read-env-reference
  "[name type] of #env \"NAME\" or #env [\"NAME\" type], or nil when it is
   neither."
  [given]
  (cond
    (and (string? given) (not (str/blank? given)))
    [given :string]

    (and (vector? given) (= 2 (count given))
         (string? (first given)) (not (str/blank? (first given)))
         (contains? env-types (second given)))
    given))

(defn- turn-env-text-into-value
  "{:value v} or {:message …}; the text itself is never in the message."
  [variable-name type ^String text]
  (case type
    :string  {:value text}
    :int     (if (re-matches #"[+-]?\d+" text)
               (try {:value (Long/parseLong text)}
                    (catch NumberFormatException _
                      {:message (str "the environment variable " variable-name " is too large a number")}))
               {:message (str "the environment variable " variable-name " is not a whole number")})
    :boolean (case text
               "true"  {:value true}
               "false" {:value false}
               {:message (str "the environment variable " variable-name " is not true or false")})))

(defn fill-env
  "[settings problems]: every #env replaced by the environment's value,
   turned into its type; a missing variable, a value that isn't its type
   or a bad #env is a problem at its path."
  [settings env]
  (let [problems (volatile! [])
        filled   (walk-with-paths
                  settings []
                  (fn [reference path]
                    (if-not (instance? EnvReference reference)
                      reference
                      (let [[variable-name type] (read-env-reference (:given reference))]
                        (if-not variable-name
                          (do (vswap! problems conj (problem path "#env takes a name, #env \"PORT\", or a name and a type, #env [\"PORT\" :int]; the types are :string, :int and :boolean"))
                              unfilled)
                          (let [text (get env variable-name)]
                            (if (nil? text)
                              (do (vswap! problems conj (problem path (str "the environment variable " variable-name " is not set")))
                                  unfilled)
                              (let [{:keys [value message]} (turn-env-text-into-value variable-name type text)]
                                (if message
                                  (do (vswap! problems conj (problem path message))
                                      unfilled)
                                  value)))))))))]
    [filled @problems]))

;; -----------------------------------------------------------------------------
;; #secret

(defn list-secret-references
  "[{:path … :secret-name …}] for every good #secret, and the problems of
   the bad ones (not a namespaced keyword)."
  [settings]
  (let [found    (volatile! [])
        problems (volatile! [])]
    (walk-with-paths settings []
                     (fn [reference path]
                       (when (instance? SecretReference reference)
                         (if (qualified-keyword? (:given reference))
                           (vswap! found conj {:path path :secret-name (:given reference)})
                           (vswap! problems conj (problem path "#secret takes a namespaced keyword, #secret :groundwork-email/postmark-token"))))
                       reference))
    [@found @problems]))

(defn fill-secrets
  "settings with every #secret replaced by (find-secret name), or by
   unfilled where it returns nil."
  [settings find-secret]
  (walk-with-paths settings []
                   (fn [reference _]
                     (if (instance? SecretReference reference)
                       (or (when (qualified-keyword? (:given reference))
                             (find-secret (:given reference)))
                           unfilled)
                       reference))))

(defn list-unfilled-paths
  "Every path whose value is unfilled."
  [value]
  (let [paths (volatile! [])]
    (letfn [(walk [v path]
              (cond
                (= unfilled v) (vswap! paths conj path)
                (map? v)       (doseq [[k x] v] (walk x (conj path k)))
                (or (vector? v) (seq? v) (set? v))
                (doseq [[i x] (map-indexed vector v)] (walk x (conj path i)))))]
      (walk value []))
    @paths))
