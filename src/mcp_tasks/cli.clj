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

(defn exit-with-success
  "Print message to stdout and exit with code 0."
  [message]
  (println message)
  (exit 0))

(defn exit-with-error
  "Print message to stderr and exit with code 1."
  [message]
  (binding [*out* *err*]
    (println message))
  (exit 1))

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
                                 :customize (prompts/prompts-customize-command resolved-config parsed-args)
                                 :show (prompts/prompts-show-command resolved-config parsed-args)))
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
          command-args (rest command-and-args)

          ;; Determine what action to take
          result (cond
                   ;; Handle global help or no command
                   (or help (nil? command))
                   {:help parse/help-text}

                   ;; Handle command-specific help
                   (and (= "--help" (first command-args))
                        (contains? valid-commands command))
                   {:help (case command
                            "list" parse/list-help
                            "show" parse/show-help
                            "add" parse/add-help
                            "complete" parse/complete-help
                            "update" parse/update-help
                            "delete" parse/delete-help
                            "reopen" parse/reopen-help
                            "why-blocked" parse/why-blocked-help
                            "prompts" parse/prompts-help)}

                   ;; Handle unknown command
                   (not (contains? valid-commands command))
                   {:error (str "Unknown command: " command "\n\n" parse/help-text)
                    :exit-code 1
                    :stderr? true}

                   ;; Execute valid command
                   :else
                   (let [{:keys [raw-config config-dir]} (config/read-config)
                         resolved-config (config/resolve-config config-dir raw-config)
                         _ (config/validate-startup config-dir resolved-config)]
                     (execute-command resolved-config command command-args format)))]

      ;; Handle result uniformly
      (cond
        ;; Help output
        (:help result)
        (do (println (:help result))
            (exit 0))

        ;; Error output
        (:error result)
        (let [{:keys [exit-code stderr?] :or {exit-code 1 stderr? true}} result]
          (if stderr?
            (binding [*out* *err*]
              (println (:error result)))
            (println (:error result)))
          (exit exit-code))

        ;; Normal output
        :else
        (let [{:keys [exit-code output stderr?]} result]
          (if stderr?
            (binding [*out* *err*]
              (println output))
            (println output))
          (exit exit-code))))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (exit-with-error
          (if (= :tool-error (:type data))
            (.getMessage e)
            (format/format-error {:error (.getMessage e)
                                  :details data})))))

    (catch Exception e
      (exit-with-error
        (format/format-error {:error (.getMessage e)})))))
