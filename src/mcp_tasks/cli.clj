(ns mcp-tasks.cli
  "Command-line interface for mcp-tasks.
  
  Entry point for the CLI that routes commands to their implementations."
  (:require
    [babashka.cli :as cli]
    [mcp-tasks.cli.commands :as commands]
    [mcp-tasks.cli.format :as format]
    [mcp-tasks.cli.parse :as parse]
    [mcp-tasks.config :as config]))

(defn exit
  "Exit the program with the given status code.
  Extracted as a separate function to allow testing without actually exiting."
  [code]
  (System/exit code))

(defn -main
  "Main entry point for the CLI."
  [& args]
  (try
    (let [;; Parse global args and extract command
          parsed (cli/parse-args args {:coerce {:format :keyword}})
          {:keys [config-path format help]} (:opts parsed)
          config-path (or config-path ".")
          format (or format :edn)
          remaining-args (:args parsed)
          command (first remaining-args)
          command-args (rest remaining-args)]

      ;; Handle global help or no command
      (cond
        (or help (nil? command))
        (do
          (println parse/help-text)
          (exit 0))

        ;; Handle command-specific help
        (and (= "--help" (first command-args))
             (contains? #{"list" "show" "add" "complete" "update" "delete"} command))
        (do
          (case command
            "list" (println parse/list-help)
            "show" (println parse/show-help)
            "add" (println parse/add-help)
            "complete" (println parse/complete-help)
            "update" (println parse/update-help)
            "delete" (println parse/delete-help))
          (exit 0))

        ;; Execute command
        :else
        (let [path (str config-path)]
          ;; Validate config path exists
          (if-not (.exists (java.io.File. path))
            (do
              (binding [*out* *err*]
                (println (format/format-error
                           {:error "Config path does not exist"
                            :path path})))
              (exit 1))

            (let [;; Load config
                  raw-config (config/read-config path)
                  resolved-config (config/resolve-config path (or raw-config {}))
                  _ (config/validate-startup path resolved-config)

                  ;; Parse command-specific args
                  parsed-args (case command
                                "list" (parse/parse-list command-args)
                                "show" (parse/parse-show command-args)
                                "add" (parse/parse-add command-args)
                                "complete" (parse/parse-complete command-args)
                                "update" (parse/parse-update command-args)
                                "delete" (parse/parse-delete command-args)
                                (do
                                  (binding [*out* *err*]
                                    (println (str "Unknown command: " command))
                                    (println)
                                    (println parse/help-text))
                                  (exit 1)))]

              ;; Check for parsing errors
              (if (:error parsed-args)
                (do
                  (binding [*out* *err*]
                    (println (format/format-error parsed-args)))
                  (exit 1))

                ;; Execute command
                (let [result (case command
                               "list" (commands/list-command resolved-config parsed-args)
                               "show" (commands/show-command resolved-config parsed-args)
                               "add" (commands/add-command resolved-config parsed-args)
                               "complete" (commands/complete-command resolved-config parsed-args)
                               "update" (commands/update-command resolved-config parsed-args)
                               "delete" (commands/delete-command resolved-config parsed-args))
                      output-format (or (:format parsed-args) format)
                      formatted-output (format/render output-format result)]
                  (if (:error result)
                    (do
                      (binding [*out* *err*]
                        (println formatted-output))
                      (exit 1))
                    (do
                      (println formatted-output)
                      (exit 0))))))))))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (binding [*out* *err*]
          (if (= :tool-error (:type data))
            (println (.getMessage e))
            (println (format/format-error {:error (.getMessage e)
                                           :details data}))))
        (exit 1)))

    (catch Exception e
      (binding [*out* *err*]
        (println (format/format-error {:error (.getMessage e)})))
      (exit 1))))
