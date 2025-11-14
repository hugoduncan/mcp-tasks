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

(def valid-commands
  #{"list" "show" "add" "complete" "update" "delete" "reopen" "why-blocked" "prompts"})

(defn exit
  "Exit the program with the given status code.
  Extracted as a separate function to allow testing without actually exiting."
  [code]
  (System/exit code))

(defn execute-command
  "Execute a command with the given configuration and arguments.
  Returns a map with :exit-code and :output."
  [resolved-config command command-args global-format]
  (let [;; Parse command-specific args
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
    (cond
      ;; Check for help request
      (:help parsed-args)
      {:exit-code 0
       :output (:help parsed-args)
       :stderr? false}

      ;; Check for parsing errors
      (:error parsed-args)
      {:exit-code 1
       :output (format/format-error parsed-args)
       :stderr? true}

      ;; Execute command
      :else
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
            output-format (or (:format parsed-args) global-format)
            formatted-output (format/render output-format result)]
        (if (:error result)
          {:exit-code 1
           :output formatted-output
           :stderr? true}
          {:exit-code 0
           :output formatted-output
           :stderr? false})))))

(defn -main
  "Main entry point for the CLI."
  [& args]
  (try
    (let [;; Find the first argument that looks like a command (not an option or option value)
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

      (cond
        ;; Handle global help or no command
        (or help (nil? command))
        (do
          (println parse/help-text)
          (exit 0))

        ;; Handle command-specific help
        (and (= "--help" (first command-args))
             (contains? valid-commands command))
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

        ;; Handle unknown command
        (not (contains? valid-commands command))
        (do
          (binding [*out* *err*]
            (println (str "Unknown command: " command))
            (println)
            (println parse/help-text))
          (exit 1))

        ;; Execute valid command
        :else
        (let [{:keys [raw-config config-dir]} (config/read-config)
              resolved-config (config/resolve-config config-dir raw-config)
              _ (config/validate-startup config-dir resolved-config)
              {:keys [exit-code output stderr?]} (execute-command resolved-config command command-args format)]
          (if stderr?
            (binding [*out* *err*]
              (println output))
            (println output))
          (exit exit-code))))

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
