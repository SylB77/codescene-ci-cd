(ns codescene-gitlab.core
  (:require [clojure.string :as string]
            [clojure.tools.cli :as cli]
            [codescene-gitlab.delta-analysis :as delta-analysis]
            [codescene-gitlab.gitlab-api :as gitlab])
  (:gen-class)
  ;;(:import (codescene_gitlab RemoteAnalysisException))
  )

(def ^:private cli-options
  [["-h" "--help"]
   ;; Codescene access settings
   [nil "--delta-analysis-url URL" "Project Delta Analysis URL"]
   ["-u" "--user USER" "CodeScene User"]
   ["-p" "--password PWD" "CodeScene Password"]
   ["-r" "--repository REPO" "Repository"]
   ;; Flags
   [nil "--analyze-latest-individually" "Individual Commits" :default false]
   [nil "--analyze-branch-diff" "By Branch" :default false]
   [nil "--use-biomarkers" "Use Biomarkers" :default false]
   [nil "--pass-on-failed-analysis" "Build Success on Failed Analysis" :default false]
   [nil "--fail-on-high-risk" "Mark as Unstable on High Risk" :default false]
   [nil "--fail-on-failed-goal" "Mark Build as Unstable on Failed Goals" :default false]
   [nil "--fail-on-declining-code-health" "Mark Build as Unstable on Code Health Decline" :default false]
   [nil "--create-gitlab-note" "Create Note For Gitlab Merge Request" :default false]
   ;; Arguments
   [nil "--coupling-threshold-percent THRESHOLD" "Temporal Coupling Threshold (in percent)" :default 75 :parse-fn #(Integer/parseInt %)]
   [nil "--risk-threshold THRESHOLD" "Risk Threshold" :default 9 :parse-fn #(Integer/parseInt %)]
   [nil "--previous-commit SHA" "Previous Commit Id"]
   [nil "--current-commit SHA" "Current Commit Id"]
   [nil "--base-revision SHA" "Base Revision Id"]
   [nil "--branch BRANCH" "Branch to analyze" :default "master"]
   [nil "--gitlab-url URL" "GitLab URL"]
   [nil "--api-token TOKEN" "GitLab API Token"]
   [nil "--project-id ID" "GitLab Project ID" :parse-fn #(Integer/parseInt %)]
   [nil "--merge-request-iid IID" "GitLab Merge Request IID" :parse-fn #(Integer/parseInt %)]
   [nil "--html-dir DIR" "Path where html output is generated"]])

(defn- usage [options-summary]
  (->> ["Usage: codescene-gitlab [options]"
        "Options:"
        options-summary]
       (string/join \newline)))

(defn- error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn- validation-error-msg [errors]
  (str "The following validation errors occurred for your command:\n\n"
       (string/join \newline errors)))

(defn- exit [success? msg]
  (println success? msg)
  (System/exit (if success? 0 1)))

(defn- validate-options [options]
  (let [{:keys [analyze-latest-individually analyze-branch-diff create-gitlab-note
                delta-analysis-url user password repository
                previous-commit current-commit base-revision
                gitlab-url api-token project-id merge-request-iid]} options]
    (filter
      some?
      (concat
        (when-not (some? delta-analysis-url) ["Delta analysis URL not specified"])
        (when-not (some? user) ["Codescene user not specified"])
        (when-not (some? password) ["Codescene password not specified"])
        (when-not (some? repository) ["Codescene repository not specified"])
        (when analyze-latest-individually
          [(when-not (some? current-commit) "Current commit not specified")
           (when-not (some? previous-commit) "Previous commit not specified")])
        (when analyze-branch-diff
          ;; TODO: Don't report this twice...
          [(when-not (some? current-commit) "Current commit not specified")
           (when-not (some? base-revision) "Base revision not specified")])
        (when create-gitlab-note
          [(when-not (some? gitlab-url) "GitLab URL not specified")
           (when-not (some? api-token) "API token not specified")
           (when-not (some? project-id) "Project Id not specified")
           (when-not (some? merge-request-iid) "Merge request IID not specified")])))))

(defn parse-args
  [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      ;; help => exit OK with usage summary
      (:help options) {:exit-message (usage summary) :ok? true}
      ;; errors => exit with description of errors
      errors {:exit-message (error-msg errors)}
      :else (let [validation-errors (validate-options options)]
              (if (seq validation-errors)
                ;; failed custom validation => exit with usage summary
                {:exit-message (validation-error-msg validation-errors)}
                ;; success => exit with options
                {:options options})))))

(defn run-analysis [options listener]
  (let [{:keys [analyze-latest-individually analyze-branch-diff previous-commit base-revision]} options]
    (concat
      (when (and analyze-latest-individually (some? previous-commit))
        (delta-analysis/analyze-latest-individual-commit-for options listener))
      (when (and analyze-branch-diff (some? base-revision))
        (delta-analysis/analyze-work-on-branch-for options listener)))))

(defn create-gitlab-note [options results]
  (let [{:keys [gitlab-url api-token project-id merge-request-iid]} options]
    (gitlab/create-merge-request-note gitlab-url api-token project-id merge-request-iid
                                      "CodeScene Analysis results.....")))

(defn -main
  [& args]
  (let [{:keys [options exit-message ok?]} (parse-args args)
        listener println]
    (if exit-message
      (exit ok? exit-message)
      (try
        (let [results (run-analysis options listener)
              success (not-any? :unstable results)]
          (when (and success (:create-gitlab-note options))
            (create-gitlab-note options results))
          (exit success ""))
        (catch Exception e
          (listener "Remote failure as CodeScene couldn't perform the delta analysis:")
          (listener e)
          (exit (:pass-on-failed-analysis options) ""))
        (catch Exception e
          (listener "Failed to run delta analysis:")
          (listener e)
          (exit false ""))))))
