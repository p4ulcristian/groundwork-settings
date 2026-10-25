(ns groundwork-settings.parts
  "A place's settings file and its parts, gathered into one map. The place
   file lists its parts under :parts, paths from the working directory;
   a part is a settings file of its own for one thing (a provider, a
   database), shared by every place that lists it. Every file says
   :secrets, the sops files of its own #secrets; a part lists no parts.
   Each top-level key is set in exactly one file: one set in two is a
   problem naming both, never one file overriding another.

     ;; settings/places/peter.edn
     {:parts   [\"settings/parts/workos/sandbox.edn\" \"settings/parts/database/laptop.edn\"]
      :secrets []
      :server  {:ip \"127.0.0.1\" :port 4100}}

     ;; settings/parts/workos/sandbox.edn
     {:secrets [{:from :sops :file \"settings/parts/workos/sandbox.sops.yaml\" …}]
      :workos  {:client-id \"client_…\" :api-key #secret :workos/api-key}}"
  (:require
   [clojure.string :as str]
   [groundwork-settings.reading :as reading]))

(defn- problem [path message]
  {:path path :message message})

(defn- check-parts-list
  "Problems of the place's :parts: a vector of paths, each once."
  [parts]
  (cond
    (not (vector? parts))
    [(problem [:parts] "should be a vector of part files, [] for none")]

    (not-every? #(and (string? %) (not (str/blank? %))) parts)
    (vec (keep-indexed (fn [i p] (when-not (and (string? p) (not (str/blank? p)))
                                   (problem [:parts i] "should be a part file's path")))
                       parts))

    (not (apply distinct? nil parts))
    [(problem [:parts] "names a part twice")]))

(defn- lists-a-file-twice?
  "Whether one file's :secrets names a sops file twice (two files may each
   name the same one: it is read once)."
  [sources]
  (let [files (keep #(when (map? %) (:file %)) sources)]
    (not= (count files) (count (distinct files)))))

(defn- read-part
  "{:settings map} or {:problems […]} for the part at index i."
  [i file]
  (let [{:keys [settings problem]} (reading/read-file file)]
    (cond
      problem                       {:problems [(assoc problem :path [:parts i])]}
      (contains? settings :parts)   {:problems [{:path [:parts i] :message (str file " lists parts: a part can't")}]}
      (not (contains? settings :secrets))
      {:problems [{:path [:parts i] :message (str file " says no :secrets: its sops files, or [] for none")}]}
      (not (vector? (:secrets settings)))
      {:problems [{:path [:parts i] :message (str file "'s :secrets should be a vector of sops files, [] for none")}]}
      (lists-a-file-twice? (:secrets settings))
      {:problems [{:path [:parts i] :message (str file "'s :secrets names a file twice")}]}
      :else                         {:settings settings})))

(defn gather
  "{:settings map :origins {key file} :problems […]} for the place file's
   map (already read): its :parts read and laid beside its own keys, one
   :secrets of every file's sops files (each once, in order), :parts left
   out. :origins names the part a key came from (the place's own keys are
   not in it). Problems mean the map can't be put together."
  [place-file place]
  (let [own-problems (concat (when-not (contains? place :parts)
                               [(problem [:parts] "missing: the place's part files, or [] for none")])
                             (cond
                               (not (contains? place :secrets))
                               [(problem [:secrets] "missing: the sops files, [{:from :sops …} …], or [] for none")]
                               (not (vector? (:secrets place)))
                               [(problem [:secrets] "should be a vector of sops files, [] for none")]
                               (lists-a-file-twice? (:secrets place))
                               [(problem [:secrets] "names a file twice")]))
        parts        (get place :parts [])
        list-problems (check-parts-list parts)]
    (if (seq (concat own-problems list-problems))
      {:settings {} :origins {} :problems (vec (concat own-problems list-problems))}
      (let [read-parts (vec (map-indexed read-part parts))
            problems   (into [] (mapcat :problems) read-parts)]
        (if (seq problems)
          {:settings {} :origins {} :problems problems}
          (let [files    (cons [place-file (dissoc place :parts :secrets)]
                               (map (fn [file {:keys [settings]}] [file (dissoc settings :secrets)]) parts read-parts))
                by-key   (group-by first (for [[file m] files, k (keys m)] [k file]))
                doubles  (for [[k sets] (sort-by (comp pr-str key) by-key)
                               :when (< 1 (count sets))]
                           (problem [k] (str k " is set in " (str/join " and " (map second sets))
                                             ": a key is set in one file")))
                sources  (vec (distinct (concat (:secrets place) (mapcat (comp :secrets :settings) read-parts))))]
            (if (seq doubles)
              {:settings {} :origins {} :problems (vec doubles)}
              {:settings (assoc (apply merge (map second files)) :secrets sources)
               :origins  (into {} (for [[file m] (rest files), k (keys m)] [k file]))
               :problems []})))))))
