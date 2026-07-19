(ns scholarly-mcp.llm-api
  "LLM API integration for server-side summarization fallbacks."
  (:require
    [cheshire.core :as json]
    [clj-http.client :as http]
    [clojure.string :as string]
    [clojure.tools.logging :as log]
    [scholarly-mcp.api :as api]))


(defn call-gemini-summary
  "Summarize paper text using Google's Gemini API with the provided API key."
  [text api-key]
  (try
    (log/info "Requesting paper summary from Gemini API")
    (let [url "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
          resp (http/post url {:query-params {:key api-key}
                               :headers {"Content-Type" "application/json"}
                               :body (json/generate-string
                                       {:contents [{:parts [{:text (str "Summarize the following academic paper in detail. Highlight the core contribution, methodology, key findings, and limitations.\n\n"
                                                                        (subs text 0 (min (count text) 120000)))}]}]})
                               :conn-timeout 30000
                               :socket-timeout 30000})
          body (api/safe-json-parse (:body resp))
          summary (get-in body [:candidates 0 :content :parts 0 :text])]
      (if (string/blank? summary)
        (do
          (log/warn "Gemini API returned empty summary response")
          (str "Gemini API call succeeded but returned empty content. Response: " (:body resp)))
        (do
          (log/info "Successfully generated summary using Gemini")
          summary)))
    (catch Exception e
      (log/error e "Gemini API call failed")
      (str "Gemini summary failed: " (.getMessage e)))))


(defn call-openai-summary
  "Summarize paper text using OpenAI's GPT model with the provided API key."
  [text api-key]
  (try
    (log/info "Requesting paper summary from OpenAI API")
    (let [url "https://api.openai.com/v1/chat/completions"
          resp (http/post url {:headers {"Authorization" (str "Bearer " api-key)
                                         "Content-Type" "application/json"}
                               :body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{"role" "system" "content" "You are a research assistant. Summarize the academic paper provided by the user. Highlight the core contribution, methodology, key findings, and limitations."}
                                                   {"role" "user" "content" (subs text 0 (min (count text) 100000))}]})
                               :conn-timeout 30000
                               :socket-timeout 30000})
          body (api/safe-json-parse (:body resp))
          summary (get-in body [:choices 0 :message :content])]
      (if (string/blank? summary)
        (do
          (log/warn "OpenAI API returned empty summary response")
          (str "OpenAI API call succeeded but returned empty content. Response: " (:body resp)))
        (do
          (log/info "Successfully generated summary using OpenAI")
          summary)))
    (catch Exception e
      (log/error e "OpenAI API call failed")
      (str "OpenAI summary failed: " (.getMessage e)))))


(defn call-anthropic-summary
  "Summarize paper text using Anthropic's Claude model with the provided API key."
  [text api-key]
  (try
    (log/info "Requesting paper summary from Anthropic API")
    (let [url "https://api.anthropic.com/v1/messages"
          resp (http/post url {:headers {"x-api-key" api-key
                                         "anthropic-version" "2023-06-01"
                                         "Content-Type" "application/json"}
                               :body (json/generate-string
                                       {:model "claude-3-5-haiku-20241022"
                                        :max_tokens 1524
                                        :messages [{"role" "user" "content" (str "Summarize the following academic paper in detail. Highlight the core contribution, methodology, key findings, and limitations.\n\n"
                                                                                 (subs text 0 (min (count text) 100000)))}]})
                               :conn-timeout 30000
                               :socket-timeout 30000})
          body (api/safe-json-parse (:body resp))
          summary (get-in body [:content 0 :text])]
      (if (string/blank? summary)
        (do
          (log/warn "Anthropic API returned empty summary response")
          (str "Anthropic API call succeeded but returned empty content. Response: " (:body resp)))
        (do
          (log/info "Successfully generated summary using Anthropic")
          summary)))
    (catch Exception e
      (log/error e "Anthropic API call failed")
      (str "Anthropic summary failed: " (.getMessage e)))))
