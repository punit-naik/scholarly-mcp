(ns scholarly-mcp.tools
  "Exposed MCP tool implementation handlers for the scholarly server."
  (:require
    [clj-http.client :as http]
    [clojure.string :as string]
    [clojure.tools.logging :as log]
    [plumcp.core.api.entity-gen :as eg]
    [plumcp.core.api.mcp-runtime :as mr]
    [plumcp.core.api.mcp-server :as ms]
    [plumcp.core.deps.runtime :as rt]
    [scholarly-mcp.api :as api]
    [scholarly-mcp.llm-api :as llm])
  (:import
    (java.io
      StringReader)
    (org.apache.lucene.analysis.standard
      StandardAnalyzer)
    (org.apache.lucene.analysis.tokenattributes
      CharTermAttribute)))


;; ==========================================
;; Utility Functions for Deduplication & Formatting
;; ==========================================

(defn clean-doi
  "Canonicalize DOI strings for consistent lookup and deduplication."
  [doi]
  (when doi
    (-> (string/lower-case doi)
        (string/replace #"https?://(dx\.)?doi\.org/" "")
        (string/trim))))


(defn clean-title
  "Normalize title strings by converting to lower case and removing non-alphanumeric chars."
  [title]
  (when title
    (-> (string/lower-case title)
        (string/replace #"[^a-z0-9]" "")
        (string/trim))))


(defn deduplicate-papers
  "Deduplicate a collection of paper maps by clean DOI first, then by clean title."
  [papers]
  (let [before-cnt (count papers)
        by-doi (group-by #(clean-doi (:doi %)) (filter :doi papers))
        no-doi (filter #(nil? (:doi %)) papers)
        ;; Merge papers with identical DOI
        merged-doi (map (fn [[_ matches]]
                          (reduce (fn [acc paper]
                                    (cond-> acc
                                      (and (not (:abstract acc)) (:abstract paper)) (assoc :abstract (:abstract paper))
                                      (> (:citation-count paper) (or (:citation-count acc) 0)) (assoc :citation-count (:citation-count paper))
                                      (:is-oa paper) (assoc :is-oa (:is-oa paper) :oa-url (:oa-url paper))
                                      (:url paper) (assoc :url (:url paper))))
                                  (first matches)
                                  (rest matches)))
                        by-doi)
        all-candidates (concat merged-doi no-doi)
        ;; Finally group by cleaned title to catch duplicates without DOIs
        by-title (group-by #(clean-title (:title %)) all-candidates)
        results (map (fn [[_ matches]]
                       (first matches))
                     by-title)]
    (log/info "Deduplication: reduced paper list size from" before-cnt "to" (count results))
    results))


(defn format-paper-markdown
  "Format a single paper's metadata into a standard Markdown item."
  [paper]
  (let [cleaned-doi (clean-doi (:doi paper))]
    (str "### " (:title paper) "\n"
         "* **Authors:** " (string/join ", " (:authors paper)) "\n"
         "* **Year:** " (:year paper) " | **Venue:** " (:venue paper) "\n"
         "* **Citations:** " (:citation-count paper) "\n"
         (when cleaned-doi (str "* **DOI:** [" cleaned-doi "](https://doi.org/" cleaned-doi ")\n"))
         (when (:is-oa paper) (str "* **Open Access:** [Yes](" (or (:oa-url paper) (:url paper)) ")\n"))
         (when (:abstract paper) (str "* **Abstract:** " (:abstract paper) "\n"))
         "\n---\n")))


;; ==========================================
;; MCP Tool: search_papers
;; ==========================================

(defn ^{:mcp-name "search_papers" :mcp-type :tool} search-papers
  "Search papers by topic across multiple academic indexes (OpenAlex, Semantic Scholar, arXiv, PubMed)."
  [{:keys [^{:doc "Search query / topic" :type "string"} query]}]
  (try
    (log/info "Calling search_papers tool for query:" query)
    (if (string/blank? query)
      (do
        (log/warn "search_papers tool received empty query")
        (eg/make-call-tool-result [(eg/make-text-content "Query cannot be empty.")]))
      (let [oa-results (api/search-openalex query)
            ss-results (api/search-semantic-scholar query)
            arxiv-results (api/search-arxiv query)
            pm-results (api/search-pubmed query)
            springer-results (api/search-springer query)
            crossref-results (api/search-crossref query)
            all-results (concat oa-results ss-results arxiv-results pm-results springer-results crossref-results)
            deduped (deduplicate-papers all-results)
            sorted (sort-by :citation-count > deduped)
            report-items (map format-paper-markdown (take 15 sorted))
            report (str "# Search Results for: " query "\n\n"
                        (if (empty? report-items)
                          "No results found."
                          (string/join "\n" report-items)))]
        (log/info "search_papers completed successfully, returning" (count report-items) "items")
        (eg/make-call-tool-result [(eg/make-text-content report)])))
    (catch Exception e
      (log/error e "Error in search-papers tool")
      (eg/make-call-tool-result [(eg/make-text-content (str "Error searching papers: " (.getMessage e)))]))))


;; ==========================================
;; MCP Tool: get_citations
;; ==========================================

(defn ^{:mcp-name "get_citations" :mcp-type :tool} get-citations
  "Find papers citing a specific DOI."
  [{:keys [^{:doc "The DOI of the paper" :type "string"} doi]}]
  (try
    (log/info "Calling get_citations tool for DOI:" doi)
    (if (string/blank? doi)
      (do
        (log/warn "get_citations tool received empty DOI")
        (eg/make-call-tool-result [(eg/make-text-content "DOI cannot be empty.")]))
      (let [clean (clean-doi doi)
            ;; Query OpenAlex citing API
            url (str "https://api.openalex.org/works?filter=cites:https://doi.org/" clean)
            resp (http/get url {:headers api/default-headers
                                :query-params {:per_page 10}})
            body (api/safe-json-parse (:body resp))
            oa-citing (map (fn [item]
                             {:title (:title item)
                              :authors (map #(get-in % [:author :display_name]) (:authorships item))
                              :year (:publication_year item)
                              :venue (get-in item [:primary_location :source :display_name])
                              :doi (:doi item)
                              :citation-count (:cited_by_count item)})
                           (:results body))
            report-items (map format-paper-markdown (take 15 oa-citing))
            report (str "# Papers citing DOI: " doi "\n\n"
                        (if (empty? report-items)
                          "No citing papers found in OpenAlex index."
                          (string/join "\n" report-items)))]
        (log/info "get_citations completed successfully, returning" (count report-items) "citations")
        (eg/make-call-tool-result [(eg/make-text-content report)])))
    (catch Exception e
      (log/error e "Error in get-citations tool")
      (eg/make-call-tool-result [(eg/make-text-content (str "Error fetching citations: " (.getMessage e)))]))))


;; ==========================================
;; MCP Tool: retrieve_full_text
;; ==========================================

(defn ^{:mcp-name "retrieve_full_text" :mcp-type :tool} retrieve-full-text
  "Retrieve metadata and link to open access full text (PDF/HTML) if available."
  [{:keys [^{:doc "The DOI of the paper" :type "string"} doi]}]
  (try
    (log/info "Calling retrieve_full_text tool for DOI:" doi)
    (if (string/blank? doi)
      (do
        (log/warn "retrieve_full_text tool received empty DOI")
        (eg/make-call-tool-result [(eg/make-text-content "DOI cannot be empty.")]))
      (let [paper (api/fetch-openalex-by-doi doi)]
        (if paper
          (let [oa-status (if (:is-oa paper) "Open Access (Free to Read)" "Paywalled / Restricted")
                report (str "# Paper Full-Text Lookup: " (:title paper) "\n\n"
                            "* **Authors:** " (string/join ", " (:authors paper)) "\n"
                            "* **Year:** " (:year paper) "\n"
                            "* **Status:** " oa-status "\n"
                            (when (:is-oa paper)
                              (str "* **OA URL:** [" (or (:oa-url paper) (:url paper)) "](" (or (:oa-url paper) (:url paper)) ")\n"))
                            (when (:abstract paper)
                              (str "\n## Abstract\n" (:abstract paper) "\n"))
                            "\n> Note: For PDFs, please use the OA URL to download the full PDF document directly.")]
            (log/info "retrieve_full_text completed successfully, resolved OA URL:" (:oa-url paper))
            (eg/make-call-tool-result [(eg/make-text-content report)]))
          (do
            (log/warn "Could not resolve DOI in OpenAlex database:" doi)
            (eg/make-call-tool-result [(eg/make-text-content (str "Could not find paper with DOI " doi " in the OpenAlex database."))])))))
    (catch Exception e
      (log/error e "Error in retrieve-full-text tool")
      (eg/make-call-tool-result [(eg/make-text-content (str "Error retrieving full text: " (.getMessage e)))]))))


;; ==========================================
;; MCP Tool: compare_papers
;; ==========================================

(defn ^{:mcp-name "compare_papers" :mcp-type :tool} compare-papers
  "Compare multiple papers side-by-side using their DOIs."
  [{:keys [^{:doc "Comma-separated list of DOIs" :type "string"} dois]}]
  (try
    (log/info "Calling compare_papers tool for DOIs:" dois)
    (if (string/blank? dois)
      (do
        (log/warn "compare_papers tool received empty DOIs list")
        (eg/make-call-tool-result [(eg/make-text-content "Please provide at least one DOI.")]))
      (let [doi-list (map string/trim (string/split dois #","))
            papers (keep api/fetch-openalex-by-doi doi-list)]
        (if (seq papers)
          (let [table-header (str "| Field | " (string/join " | " (map #(str "Paper " %1) (range 1 (inc (count papers))))) " |\n")
                separator-line (str "| --- | " (string/join " | " (repeat (count papers) "---")) " |\n")
                title-row (str "| Title | " (string/join " | " (map :title papers)) " |\n")
                authors-row (str "| Authors | " (string/join " | " (map #(string/join ", " (take 2 (:authors %))) papers)) " |\n")
                year-row (str "| Year | " (string/join " | " (map :year papers)) " |\n")
                venue-row (str "| Venue | " (string/join " | " (map :venue papers)) " |\n")
                citations-row (str "| Citations | " (string/join " | " (map :citation-count papers)) " |\n")
                oa-row (str "| Open Access | " (string/join " | " (map #(if (:is-oa %) "Yes" "No") papers)) " |\n")

                abstracts (string/join "\n\n" (map-indexed (fn [idx paper]
                                                             (str "### Paper " (inc idx) ": " (:title paper) "\n"
                                                                  (or (:abstract paper) "No abstract available.")))
                                                           papers))
                report (str "# Side-by-Side Paper Comparison\n\n"
                            table-header separator-line title-row authors-row year-row venue-row citations-row oa-row "\n"
                            "## Abstracts Comparison\n\n"
                            abstracts)]
            (log/info "compare_papers completed successfully comparing" (count papers) "papers")
            (eg/make-call-tool-result [(eg/make-text-content report)]))
          (do
            (log/warn "None of the provided DOIs could be resolved:" dois)
            (eg/make-call-tool-result [(eg/make-text-content "None of the provided DOIs could be resolved in OpenAlex.")])))))
    (catch Exception e
      (log/error e "Error in compare-papers tool")
      (eg/make-call-tool-result [(eg/make-text-content (str "Error comparing papers: " (.getMessage e)))]))))


;; ==========================================
;; MCP Tool: build_literature_review
;; ==========================================

(defn ^{:mcp-name "build_literature_review" :mcp-type :tool} build-literature-review
  "Synthesize and group papers for a literature review on a topic."
  [{:keys [^{:doc "Topic search term" :type "string"} query]}]
  (try
    (log/info "Calling build_literature_review tool for query:" query)
    (if (string/blank? query)
      (do
        (log/warn "build_literature_review tool received empty query")
        (eg/make-call-tool-result [(eg/make-text-content "Query cannot be empty.")]))
      (let [oa-results (api/search-openalex query)
            ss-results (api/search-semantic-scholar query)
            merged (deduplicate-papers (concat oa-results ss-results))
            sorted (sort-by :year > merged)
            ;; Group by publication year decadal bands or just group by venue
            by-venue (group-by :venue (take 15 sorted))
            review-body (string/join "\n" (for [[venue venue-papers] by-venue
                                                :when venue]
                                            (str "### Venue: " venue "\n\n"
                                                 (string/join "\n" (map (fn [p]
                                                                          (str "* **" (:title p) "** (" (:year p) ") by " (string/join ", " (take 3 (:authors p))) "\n"
                                                                               "  *Citation Count:* " (:citation-count p) "\n"
                                                                               (when (:abstract p) (str "  *Summary:* " (subs (:abstract p) 0 (min (count (:abstract p)) 180)) "...\n"))))
                                                                        venue-papers))
                                                 "\n")))]
        (log/info "build_literature_review completed successfully, generated synthesis for" (count sorted) "papers")
        (eg/make-call-tool-result [(eg/make-text-content (str "# Literature Synthesis: " query "\n\n"
                                                              "This report organizes the top recent papers found by publication venue to assist in drafting a literature review.\n\n"
                                                              review-body))])))
    (catch Exception e
      (log/error e "Error in build-literature-review tool")
      (eg/make-call-tool-result [(eg/make-text-content (str "Error building literature review: " (.getMessage e)))]))))


;; ==========================================
;; MCP Tool: export_citations
;; ==========================================

(defn ^{:mcp-name "export_citations" :mcp-type :tool} export-citations
  "Export references for DOIs in BibTeX or RIS format."
  [{:keys [^{:doc "Comma-separated list of DOIs" :type "string"} dois
           ^{:doc "Format: 'bibtex' or 'ris'" :type "string" :default "bibtex"} format]}]
  (try
    (log/info "Calling export_citations tool for DOIs:" dois "and format:" format)
    (if (string/blank? dois)
      (do
        (log/warn "export_citations tool received empty DOIs list")
        (eg/make-call-tool-result [(eg/make-text-content "DOIs list cannot be empty.")]))
      (let [doi-list (map string/trim (string/split dois #","))
            mime-type (if (= (string/lower-case format) "ris")
                        "application/x-research-info-systems"
                        "application/x-bibtex")
            citations (map (fn [doi]
                             (str "% DOI: " doi "\n"
                                  (api/fetch-citation-format doi mime-type) "\n"))
                           doi-list)
            report (string/join "\n" citations)]
        (log/info "export_citations completed successfully exporting" (count doi-list) "citations")
        (eg/make-call-tool-result [(eg/make-text-content report)])))
    (catch Exception e
      (log/error e "Error in export-citations tool")
      (eg/make-call-tool-result [(eg/make-text-content (str "Error exporting citations: " (.getMessage e)))]))))


(defn extract-keywords
  "Extract the top keywords from a text snippet using Lucene's StandardAnalyzer."
  [text max-keywords]
  (log/info "Extracting up to" max-keywords "keywords from text snippet of size" (count text))
  (if (string/blank? text)
    ""
    (let [analyzer (StandardAnalyzer.)
          token-stream (.tokenStream analyzer "field" (StringReader. text))
          term-attr (.addAttribute token-stream CharTermAttribute)]
      (try
        (.reset token-stream)
        (loop [words []]
          (if (.incrementToken token-stream)
            (recur (conj words (.toString term-attr)))
            (->> words
                 (filter #(> (count %) 3))
                 (frequencies)
                 (sort-by second >)
                 (map first)
                 (take max-keywords)
                 (string/join " "))))
        (finally
          (.end token-stream)
          (.close token-stream)
          (.close analyzer))))))


;; ==========================================
;; MCP Tool: find_nature_papers_by_abstract
;; ==========================================

(defn ^{:mcp-name "find_nature_papers_by_abstract" :mcp-type :tool} find-nature-papers-by-abstract
  "Find related Nature journal papers based on an abstract snippet."
  [{:keys [^{:doc "The abstract text to search similarities for" :type "string"} abstract]}]
  (try
    (log/info "Calling find_nature_papers_by_abstract tool")
    (if (string/blank? abstract)
      (do
        (log/warn "find_nature_papers_by_abstract tool received empty abstract")
        (eg/make-call-tool-result [(eg/make-text-content "Abstract cannot be empty.")]))
      (let [keywords (extract-keywords abstract 4)
            ;; Search Nature papers on OpenAlex and Semantic Scholar using keywords
            search-query (str keywords " venue:Nature")
            _ (log/info "Nature lookup search query:" search-query)
            results-oa (api/search-openalex search-query)
            results-ss (api/search-semantic-scholar keywords)
            combined (concat results-oa results-ss)
            ;; Filter where venue name contains "Nature"
            nature-papers (filter (fn [p]
                                    (and (:venue p)
                                         (string/includes? (string/lower-case (:venue p)) "nature")))
                                  combined)
            deduped (deduplicate-papers nature-papers)
            report-items (map format-paper-markdown (take 10 (sort-by :citation-count > deduped)))
            report (str "# Related Nature Papers found by Abstract Keywords (" keywords ")\n\n"
                        (if (empty? report-items)
                          "No related papers in Nature journals found."
                          (string/join "\n" report-items)))]
        (log/info "find_nature_papers_by_abstract completed successfully, resolved" (count report-items) "Nature papers")
        (eg/make-call-tool-result [(eg/make-text-content report)])))
    (catch Exception e
      (log/error e "Error in find-nature-papers-by-abstract tool")
      (eg/make-call-tool-result [(eg/make-text-content (str "Error searching Nature papers: " (.getMessage e)))]))))


;; ==========================================
;; MCP Tool: summarize_paper
;; ==========================================

(defonce ^{:doc "Atom mapping JSON-RPC message request IDs to promises waiting for client sampling callbacks."}
  pending-promises
  (atom {}))


(defn ^{:mcp-type :callback :mcp-name "summarize-paper-callback"} summarize-paper-callback
  "Callback handler registered with the client to deliver the sampled message result."
  [{:as result}]
  (let [req-id (mr/get-request-id result)
        p (get @pending-promises req-id)]
    (log/info "Received summarize-paper-callback for request ID:" req-id)
    (if p
      (do
        (log/info "Delivering result to pending promise for request ID:" req-id)
        (deliver p result))
      (log/warn "No pending promise found for request ID:" req-id))
    (swap! pending-promises dissoc req-id)))


(defn- client-supports-sampling?
  "Check whether the current client supports native sampling capability."
  [kwargs]
  (let [supported (and (rt/has-session? kwargs)
                       (boolean (get-in (ms/get-client-capabilities kwargs) [:sampling])))]
    (log/debug "Client sampling support check:" supported)
    supported))


(defn- extract-text-from-sampling-result
  "Extract content text from the client's sampling/createMessage result schema."
  [result]
  (let [content (:content result)]
    (cond
      (map? content) (:text content)
      (coll? content) (:text (first content))
      :else nil)))


(defn- call-client-sampling
  "Invoke the MCP client's sampling mechanism to prompt the user's host LLM."
  [kwargs paper-text]
  (log/info "Initiating client-side sampling request for paper summary")
  (let [prompt-text (str "Summarize the following academic paper in detail. Highlight the core contribution, methodology, key findings, and limitations.\n\n"
                         (subs paper-text 0 (min (count paper-text) 120000)))
        sampling-msg (eg/make-sampling-message "user" (eg/make-text-content prompt-text))
        request (eg/make-create-message-request
                  [sampling-msg]
                  2048
                  :systemPrompt "You are an expert research scientist. Provide a clear, detailed, and structured summary of the academic paper.")
        req-id (:id request)
        p (promise)
        callback-ctx (ms/make-callback-context "summarize-paper-callback")]
    (swap! pending-promises assoc req-id p)
    (try
      (log/info "Sending sampling/createMessage request to client, ID:" req-id)
      (ms/send-request-to-client kwargs request callback-ctx)
      ;; Wait for up to 60 seconds for the response
      (let [result (deref p 60000 :timeout)]
        (if (= result :timeout)
          (do
            (log/warn "Client sampling request ID:" req-id "timed out after 60 seconds")
            (swap! pending-promises dissoc req-id)
            nil)
          (do
            (log/info "Successfully received sampling result from client for ID:" req-id)
            (extract-text-from-sampling-result result))))
      (catch Exception e
        (log/error e "Error during client sampling invocation for ID:" req-id)
        (swap! pending-promises dissoc req-id)
        nil))))


(defn ^{:mcp-name "summarize_paper" :mcp-type :tool} summarize-paper
  "Extract and summarize the text of an academic paper using its DOI or direct URL."
  [{:keys [^{:doc "The DOI of the paper" :type "string"} doi
           ^{:doc "Direct URL to PDF or HTML page" :type "string"} url] :as kwargs}]
  (try
    (log/info "Calling summarize_paper tool with DOI:" doi "and URL:" url)
    (cond
      (and (string/blank? doi) (string/blank? url))
      (do
        (log/warn "summarize_paper received empty DOI and URL parameters")
        (eg/make-call-tool-result [(eg/make-text-content "Either 'doi' or 'url' must be provided.")]))

      :else
      (let [target-url (cond
                         (not (string/blank? url))
                         url

                         (not (string/blank? doi))
                         (let [res (retrieve-full-text {:doi doi})
                               report-text (-> res :content first :text)]
                           (or (when report-text
                                 (second (re-find #"\* \*\*OA URL:\*\* \[(https?://[^\]]+)\]" report-text)))
                               (str "https://doi.org/" (clean-doi doi)))))]
        (log/info "Resolved target URL for paper summarization:" target-url)
        (if (string/blank? target-url)
          (do
            (log/warn "Could not resolve any valid URL for DOI:" doi)
            (eg/make-call-tool-result [(eg/make-text-content (str "Could not resolve a valid URL for DOI: " doi))]))
          (let [paper-text (api/download-and-extract-paper target-url)
                _ (log/info "Successfully extracted paper text length:" (count paper-text))
                summary-report (or (when (client-supports-sampling? kwargs)
                                     (call-client-sampling kwargs paper-text))
                                   ;; Fallback to server-side LLMs if configured, or text fallback
                                   (let [gemini-key (api/env-var "GEMINI_API_KEY")
                                         openai-key (api/env-var "OPENAI_API_KEY")
                                         anthropic-key (api/env-var "ANTHROPIC_API_KEY")]
                                     (cond
                                       (not (string/blank? gemini-key))
                                       (do
                                         (log/info "Running fallback: Gemini server summary")
                                         (llm/call-gemini-summary paper-text gemini-key))

                                       (not (string/blank? openai-key))
                                       (do
                                         (log/info "Running fallback: OpenAI server summary")
                                         (llm/call-openai-summary paper-text openai-key))

                                       (not (string/blank? anthropic-key))
                                       (do
                                         (log/info "Running fallback: Anthropic server summary")
                                         (llm/call-anthropic-summary paper-text anthropic-key))

                                       :else
                                       (do
                                         (log/info "Running fallback: Local structural fallback summary")
                                         (api/summarize-text-fallback paper-text)))))]
            (eg/make-call-tool-result [(eg/make-text-content summary-report)])))))
    (catch Exception e
      (log/error e "Error in summarize-paper tool")
      (eg/make-call-tool-result [(eg/make-text-content (str "Error summarizing paper: " (.getMessage e)))]))))
