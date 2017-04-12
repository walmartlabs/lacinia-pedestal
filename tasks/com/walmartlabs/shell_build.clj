(ns com.walmartlabs.shell-build
  (:require [robert.hooke :as hooke]
            [leiningen.compile :as lcompile]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as string]))

(defn compile-hook [task & args]
  (apply task args)
  (let [project (first args)]
    ;; In a multi-project build, the hook seems to persist from one sub-project
    ;; to another after it is first attached (i.e., in onboarding, then a fail
    ;; in the next project, missing-tc-report). Leiningen is a bit of a mess.
    (when-let [{:keys [dir command]} (:shell-build project)]
      (when (string/blank? command)
        (throw (ex-info ":command option is not provided for shell-build"
                 (select-keys project [:shell-build]))))
      (let [path (str (:root project) "/" dir)]
        (println (format "Executing `%s' in `%s'" command path))
        (let [{:keys [out err exit]} (sh command :dir path)]
          (println out)
          (println err)
          (when (not (zero? exit))
            (throw (ex-info (str command " exited with non-zero code " exit) {})))
          ; In some cases build command can return 0 but still fail.
          ; This is an additional safety belt - checking if err contains
          ; any indication of an error.
          (when (and err (re-find #"[Ee]rror|[Ee]xception" err))
            (throw (ex-info (str command " had errors in its output") {}))))))))

(defn activate
  []
  (hooke/add-hook #'lcompile/compile #'compile-hook))
