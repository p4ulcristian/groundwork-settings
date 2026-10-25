(ns groundwork-settings.sources
  "Where the secrets come from: the sops files the settings file lists
   under :secrets, one `sops --decrypt` of each with its age key, the
   output read in memory. A secret lives in exactly one of them: one found
   in two is a problem, not a choice. sops is found on the PATH of the
   environment groundwork-settings was given and runs with only the
   variables it needs; nothing it prints is written anywhere."
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
  "One sops file of :secrets, every key required:
   {:from :sops :file \"secrets/production.sops.yaml\" :age-key-file \"/run/secrets/age-key\" :timeout-ms 30000}:
   sops decrypts :file with the age key in :age-key-file; a secret
   :groundwork-email/postmark-token is the file's key postmark-token under
   groundwork-email. :timeout-ms: how long one run of sops may take."
  [:map {:closed true}
   [:from [:= :sops]]
   [:file [:string {:min 1}]]
   [:age-key-file [:string {:min 1}]]
   [:timeout-ms [:int {:min 1}]]])

(def sources-shape
  "The :secrets: the sops files a place opens, [] for none, each file
   once."
  [:and
   [:vector {:error/message "should be a vector of sops files, [] for none"} source-shape]
   [:fn {:error/message "names a file twice"}
    #(or (not (sequential? %)) (empty? %) (apply distinct? (map :file %)))]])

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

(defn- check-readable [index path key what]
  (when-not (.canRead (io/file path))
    (problem [:secrets index key] (str "the " what " " path " can't be read"))))

(defn- find-in-decrypted
  "{:secret s}, {:message …} (there, but not text) or nil (not there) for
   one name in sops's decrypted tree."
  [tree file secret-name]
  (let [group (get tree (namespace secret-name))
        text  (when (map? group) (get group (name secret-name)))]
    (cond
      (string? text) {:secret (secret/make-secret secret-name text)}
      (nil? text)    nil
      :else          {:message (str secret-name " in " file " is not text: write it quoted")})))

(defn read-from-sops
  "{:there {name result} :problems […]}: one `sops --decrypt --output-type
   json` of the file, with only PATH and SOPS_AGE_KEY_FILE set, so the key
   it uses is the named one. A result is {:secret s} or {:message …};
   a name the file doesn't have isn't in :there. index is the file's place
   in :secrets, for the problems' paths."
  [index {:keys [file age-key-file timeout-ms]} env secret-names]
  (let [sops     (find-program env "sops")
        problems (remove nil? [(check-readable index file :file "sops file")
                               (check-readable index age-key-file :age-key-file "age key file")
                               (when-not sops (problem [:secrets index] "no sops on the PATH"))])]
    (if (seq problems)
      {:there {} :problems (vec problems)}
      (let [{:keys [timed-out? exit out err]}
            (run-program [sops "--decrypt" "--output-type" "json" file]
                         {"PATH" (get env "PATH") "SOPS_AGE_KEY_FILE" age-key-file}
                         timeout-ms)
            tree (when (= 0 exit)
                   ;; Jackson's parse errors quote the text they read: never shown
                   (try (json/read-value out) (catch Exception _ ::not-json)))]
        (cond
          timed-out?
          {:there {} :problems [(problem [:secrets index] (str "sops took longer than " timeout-ms " ms to decrypt " file))]}

          (not= 0 exit)
          {:there {} :problems [(problem [:secrets index] (str "sops could not decrypt " file ": " (say-briefly err)))]}

          (not (map? tree))
          {:there {} :problems [(problem [:secrets index] (str "sops's output for " file " is not a JSON object"))]}

          :else
          {:there    (into {} (keep (fn [n] (when-let [r (find-in-decrypted tree file n)] [n r]))) secret-names)
           :problems []})))))

(defn- say-not-found [secret-name files]
  (str secret-name (if (= 1 (count files))
                     (str " is not in " (first files))
                     (str " is in none of " (str/join ", " files)))
       ": no key " (name secret-name) " under " (namespace secret-name)))

(defn read-secrets
  "{:found {name secret} :missing {name message} :problems […]} for
   secret-names from the sources (sources-shape's), each file decrypted
   once. A name in no file, or in two, is missing, with why. When a file
   can't be read, only its problems are said: what it holds is unknown."
  [sources env secret-names]
  (let [reads    (vec (map-indexed (fn [i source] (read-from-sops i source env secret-names)) sources))
        problems (into [] (mapcat :problems) reads)
        files    (mapv :file sources)]
    (if (seq problems)
      {:found {} :missing {} :problems problems}
      (let [results (into {}
                          (for [n secret-names
                                :let [homes (keep-indexed (fn [i {:keys [there]}] (when-let [r (get there n)] [(nth files i) r])) reads)]]
                            [n (case (count homes)
                                 0 {:message (say-not-found n files)}
                                 1 (second (first homes))
                                 {:message (str n " is in " (str/join " and " (map first homes))
                                                ": a secret lives in one file")})]))]
        {:found    (into {} (keep (fn [[n r]] (when (:secret r) [n (:secret r)]))) results)
         :missing  (into {} (keep (fn [[n r]] (when (:message r) [n (:message r)]))) results)
         :problems []}))))
