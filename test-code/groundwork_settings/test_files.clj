(ns groundwork-settings.test-files
  "Throwaway folders in the system's temporary folder, settings files and
   fake programs in them, for the tests."
  (:require
   [clojure.java.io :as io])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(defn make-folder
  "A new empty folder under the temporary folder, deleted when the JVM ends."
  []
  (let [folder (.toFile (Files/createTempDirectory "groundwork-settings-test-" (make-array FileAttribute 0)))]
    (.deleteOnExit folder)
    folder))

(defn write-file
  "Writes text to name in folder; returns its absolute path."
  [folder file-name text]
  (let [f (io/file folder file-name)]
    (io/make-parents f)
    (spit f text)
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(defn write-program
  "Writes an executable script to <folder>/bin/<name>; returns the bin folder."
  [folder program-name text]
  (let [path (write-file folder (str "bin/" program-name) text)]
    (.setExecutable (io/file path) true)
    (.getParent (io/file path))))

(defn system-path []
  (System/getenv "PATH"))
