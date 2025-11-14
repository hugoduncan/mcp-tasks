(ns mcp-tasks.cli
  "Command-line interface for mcp-tasks.

  Entry point for the CLI that routes commands to their implementations."
  (:require
    [babashka.cli :as cli]
    [clojure.string :as str]
    [mcp-tasks.cli.commands :as commands]
    [mcp-tasks.cli.format :as format]
    [mcp-tasks.cli.parse :as parse]
    [mcp-tasks.cli.prompts :as prompts]
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
    (let [;; Find the first argument that looks like a command (not an option or option value)
          ;; Valid commands are known strings that don't start with --
          valid-commands #{"list" "show" "add" "complete" "update" "delete" "reopen" "why-blocked" "prompts"}
          ;; Find command by looking for first valid command OR first non-option after options
          command-idx (loop [idx 0
                             prev-was-option? false]
                        (if (>= idx (count args))
                          idx
                          (let [arg (nth args idx)]
                            (cond
                              ;; Found a valid command
                              (valid-commands arg) idx
                              ;; This is an option flag, skip it and mark that next might be its value
                              (str/starts-with? arg "--") (recur (inc idx) true)
                              ;; Previous was option, this is its value, skip it
                              prev-was-option? (recur (inc idx) false)
                              ;; Not an option and not preceded by option flag - must be command
                              :else idx))))
          global-args (take command-idx args)
          command-and-args (drop command-idx args)

          ;; Parse only global args that appear before the command
          parsed (cli/parse-args global-args {:coerce {:format :keyword}})
          {:keys [format help]} (:opts parsed)
          format (or format :human)
          command (first command-and-args)
          command-args (rest command-and-args)]

      ;; Handle global help or no command
      (cond
        (or help (nil? command))
        (do
          (println parse/help-text)
          (exit 0))

        ;; Handle command-specific help
        (and (= "--help" (first command-args))
             (contains? #{"list" "show" "add" "complete" "update" "delete" "reopen" "why-blocked" "prompts"} command))
        (do
          (case command
            "list" (println parse/list-help)
            "show" (println parse/show-help)
            "add" (println parse/add-help)
            "complete" (println parse/complete-help)
            "update" (println parse/update-help)
            "delete" (println parse/delete-help)
            "reopen" (println parse/reopen-help)
            "why-blocked" (println parse/why-blocked-help)
            "prompts" (println parse/prompts-help))
          (exit 0))

        ;; Execute command
        :else
        (let [valid-commands #{"list" "show" "add" "complete" "update" "delete" "reopen" "why-blocked" "prompts"}]
          ;; Validate command is known
          (if-not (contains? valid-commands command)
            (do
              (binding [*out* *err*]
                (println (str "Unknown command: " command))
                (println)
                (println parse/help-text))
              (exit 1))

            ;; Check for prompts subcommand-specific help
            (if (and (= "prompts" command)
                     (>= (count command-args) 2)
                     (or (= "--help" (second command-args))
                         (= "-h" (second command-args))))
              (let [subcommand (first command-args)]
                (case subcommand
                  "list" (do (println parse/prompts-list-help) (exit 0))
                  "install" (do (println parse/prompts-install-help) (exit 0))
                  ;; Unknown subcommand, let parse-prompts handle it
                  (let [parsed-args (parse/parse-prompts command-args)]
                    (binding [*out* *err*]
                      (println (format/format-error parsed-args)))
                    (exit 1))))

              ;; Load config using automatic discovery
              (let [{:keys [raw-config config-dir]} (config/read-config)
                    resolved-config (config/resolve-config config-dir raw-config)
                    _ (config/validate-startup config-dir resolved-config)

                    ;; Parse command-specific args
                    parsed-args (case command
                                  "list" (parse/parse-list command-args)
                                  "show" (parse/parse-show command-args)
                                  "add" (parse/parse-add command-args)
                                  "complete" (parse/parse-complete command-args)
                                  "update" (parse/parse-update command-args)
                                  "delete" (parse/parse-delete command-args)
                                  "reopen" (parse/parse-reopen command-args)
                                  "why-blocked" (parse/parse-why-blocked command-args)
                                  "prompts" (parse/parse-prompts command-args))]

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
                                 "delete" (commands/delete-command resolved-config parsed-args)
                                 "reopen" (commands/reopen-command resolved-config parsed-args)
                                 "why-blocked" (commands/why-blocked-command resolved-config parsed-args)
                                 "prompts" (case (:subcommand parsed-args)
                                             :list (prompts/prompts-list-command parsed-args)
                                             :install (prompts/prompts-install-command resolved-config parsed-args)))
                        output-format (or (:format parsed-args) format)
                        formatted-output (format/render output-format result)]
                    (if (:error result)
                      (do
                        (binding [*out* *err*]
                          (println formatted-output))
                        (exit 1))
                      (do
                        (println formatted-output)
                        (exit 0)))))))))))

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
