(ns dumch.dartclojure
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [dumch.convert :refer [convert]]
            [zprint.core :as zpr])
  (:gen-class))

(set! *warn-on-reflection* true)

(def cli-options 
  [["-r" "--repl REPL" "Mode: repl or single command, single by default"
    :default false
    :parse-fn #(Boolean/parseBoolean %)
    :validate [boolean? "Must be either true or false"]]
   ["-e" "--end TEXT" "Mode: text or symbol to show the end of code"
    :default ""
    :parse-fn str]
   ["-m" "--material MATERIAL" "material require alias, any string"
    :default "m"
    :parse-fn str
    :validate [#(re-matches #"^[A-Za-z][A-Za-z0-9]*$" %)
               "invalid require-alias for material"]]
   ["-f" "--flutter FLUTTER_MACRO_PACKAGE" "flutter-macro require alias, any string"
    :default "f"
    :parse-fn str
    :validate [#(re-matches #"^[A-Za-z][A-Za-z0-9]*$" %) 
               "invalid require-alias for flutter-macro"]]
   ["-c" "--colors BOOLEAN" "colorize output"
    :default false
    :parse-fn #(Boolean/parseBoolean %)
    :validate [boolean? "Must be either true or false"]]
   ["-p" "--path PATH TO FILE, OR URL" "url or path to dart file to translate"
    :parse-fn str]
   ["-h" "--help"]])

(defn show! [data & {:keys [colors] :or {colors false}}]
  (if colors
    (zpr/czprint data {:parse-string? true})
    (println data)))

(defn- stdin-loop! [& {:keys [material flutter end colors]}]
  (println (str "Paste dart code below, press enter"
                (when (seq end) (str ", write '" end "',"))
                " and see the result:\n"))
  (loop [input (read-line)
         acc []]
    (if (= input end)
      (do 
        (try 
          (-> (convert (str/join "\n" acc) 
                      :material material 
                      :flutter flutter)
              (show! :colors colors))
          (catch Exception e (println "Can't convert the code; e " e)))
        (println)
        (recur (read-line) []))
      (recur (read-line) (conj acc input)))))

(defn -main [& args] 
  (let [{{:keys [repl help path] :as params} :options, 
         errors :errors, 
         args :arguments :as m} 
        (parse-opts args cli-options)
        params (mapcat identity params)]
    (cond 
      help (println "here is the arguments\n" m)
      errors (println errors)
      repl (apply stdin-loop! params)
      path (if-let [code (slurp path)]
             (apply show! 
                    (apply convert code params)
                    params))
      :else (if args
              (apply show! 
                     (apply convert (first args) params)
                     params)
              (println "no arguments passed")))))
