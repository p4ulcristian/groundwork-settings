(ns groundwork-settings.sources
  "Where the secrets come from: the sops file the settings file names
   under :secrets, one `sops --decrypt` of it with the age key, its output
   read in memory. sops is found on the PATH of the environment
   groundwork-settings was given and runs with only the variables it
   needs; nothing it prints is written anywhere."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [jsonista.core :as json]
   [groundwork-settings.secret :as secret])
  (:import
   (java.io File InputStream)
   (java.nio.charset StandardCharsets)
   (java.util.concurrent TimeUnit)))

(def source-shape
  "The :secrets source, every key required:
   {:from :sops :file \"secrets/production.sops.yaml\" :age-key-file \"/run/secrets/age-key\" :timeout-ms 30000}:
   sops decrypts :file with the age key in :age-key-file; a secret
   :groundwork-email/postmark-token is the file's key postmark-token under
   groundwork-email. :timeout-ms: how long one run of sops may take."
  [:map {:closed true}
   [:from [:= :sops]]
   [:file [:string {:min 1}]]
   [:age-key-file [:string {:min 1}]]
   [:timeout-ms [:int {:min 1}]]])

(defn- problem [path message]
  {:path path :message message})

;; -----------------------------------------------------------------------------
;; Running a program

(defn find-program
  "The program's absolute path on the environment's PATH, or nil."
  [env program-name]
  (when-let [path (get env "PATH")]
    (some (fn [folder]
            (let [f (File. ^String folder ^String program-name)]
              (when (and (seq folder) (.isFile f) (.canExecute f))
                (.getAbsolutePath f))))
          (str/split path #":"))))

(defn- read-all-text ^String [^InputStream stream]
  (String. (.readAllBytes stream) StandardCharsets/UTF_8))

(defn run-program
  "{:exit n :out text :err text}, or {:timed-out? true} after timeout-ms
   (the program is killed, by its process). The program gets only the
   variables in program-env, and no standard input; its output is read
   in memory."
  [command program-env timeout-ms]
  (let [builder (ProcessBuilder. ^java.util.List (vec command))
        _       (doto (.environment builder)
                  (.clear)
                  (.putAll ^java.util.Map program-env))
        process (try (.start builder)
                     (catch java.io.IOException e e))]
    (if (instance? Exception process)
      {:exit -1 :out "" :err (str "could not start " (first command) ": " (ex-message process))}
      (let [^Process process process
            _   (.close (.getOutputStream process))
            out (future (read-all-text (.getInputStream process)))
            err (future (read-all-text (.getErrorStream process)))]
        (if (.waitFor process timeout-ms TimeUnit/MILLISECONDS)
          {:exit (.exitValue process) :out @out :err @err}
          (do (.destroyForcibly process)
              (.waitFor process)
              {:timed-out? true}))))))

(defn- say-briefly
  "A program's standard error, as one short line."
  [^String err]
  (let [line (->> (str/split-lines (or err ""))
                  (map str/trim)
                  (remove str/blank?)
                  (str/join " "))]
    (if (> (count line) 400) (str (subs line 0 400) "…") line)))

;; -----------------------------------------------------------------------------
;; sops

(defn- check-readable [path key what]
  (when-not (.canRead (io/file path))
    (problem [:secrets key] (str "the " what " " path " can't be read"))))

(defn- find-in-decrypted
  "{:secret s} or {:message …} for one name in sops's decrypted tree."
  [tree file secret-name]
  (let [group (get tree (namespace secret-name))
        text  (when (map? group) (get group (name secret-name)))]
    (cond
      (string? text) {:secret (secret/make-secret secret-name text)}
      (nil? text)    {:message (str secret-name " is not in " file ": no key " (name secret-name)
                                    " under " (namespace secret-name))}
      :else          {:message (str secret-name " in " file " is not text: write it quoted")})))

(defn read-from-sops
  "{:found {name secret} :missing {name message} :problems […]}: one
   `sops --decrypt --output-type json` of the file, with only PATH and
   SOPS_AGE_KEY_FILE set, so the key it uses is the named one."
  [{:keys [file age-key-file timeout-ms]} env secret-names]
  (let [sops     (find-program env "sops")
        problems (remove nil? [(check-readable file :file "sops file")
                               (check-readable age-key-file :age-key-file "age key file")
                               (when-not sops (problem [:secrets :from] "no sops on the PATH"))])]
    (if (seq problems)
      {:found {} :missing {} :problems (vec problems)}
      (let [{:keys [timed-out? exit out err]}
            (run-program [sops "--decrypt" "--output-type" "json" file]
                         {"PATH" (get env "PATH") "SOPS_AGE_KEY_FILE" age-key-file}
                         timeout-ms)
            tree (when (= 0 exit)
                   ;; Jackson's parse errors quote the text they read: never shown
                   (try (json/read-value out) (catch Exception _ ::not-json)))]
        (cond
          timed-out?
          {:found {} :missing {} :problems [(problem [:secrets] (str "sops took longer than " timeout-ms " ms to decrypt " file))]}

          (not= 0 exit)
          {:found {} :missing {} :problems [(problem [:secrets] (str "sops could not decrypt " file ": " (say-briefly err)))]}

          (not (map? tree))
          {:found {} :missing {} :problems [(problem [:secrets] (str "sops's output for " file " is not a JSON object"))]}

          :else
          (let [results (into {} (map (juxt identity #(find-in-decrypted tree file %))) secret-names)]
            {:found    (into {} (keep (fn [[n r]] (when (:secret r) [n (:secret r)]))) results)
             :missing  (into {} (keep (fn [[n r]] (when (:message r) [n (:message r)]))) results)
             :problems []}))))))

(defn read-secrets
  "The source's secrets for secret-names: {:found :missing :problems}."
  [source env secret-names]
  (case (:from source)
    :sops (read-from-sops source env secret-names)))
