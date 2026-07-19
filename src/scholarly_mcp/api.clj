(ns scholarly-mcp.api
  "Academic database REST client integrations and file extractors."
  (:require
    [cheshire.core :as json]
    [clj-http.client :as http]
    [clojure.string :as string]
    [clojure.tools.logging :as log]
    [clojure.xml :as xml])
  (:import
    (java.io
      ByteArrayInputStream)
    (org.apache.pdfbox.pdmodel
      PDDocument)
    (org.apache.pdfbox.text
      PDFTextStripper)
    (org.jsoup
      Jsoup)))


(def default-headers
  "Polite request headers identifying this MCP server for academic APIs."
  {"User-Agent" "ResearchMCP/0.1.0 (mailto:punit.naik@gmail.com; local development)"})


(defn log-error
  "Log an error message to both standard error and tools.logging."
  [& args]
  (let [msg (string/join " " args)]
    (log/error msg)
    (binding [*out* *err*]
      (println msg))))


(defn safe-json-parse
  "Parse a JSON string safely, returning nil on exception."
  [s]
  (try
    (json/parse-string s true)
    (catch Exception e
      (log/debug e "Failed to parse JSON string")
      nil)))


(defn env-var
  "Retrieve an environment variable by name."
  [name]
  (System/getenv name))


;; ==========================================
;; OpenAlex API
;; ==========================================

(defn reconstruct-abstract
  "Reconstruct paper abstract from OpenAlex's inverted index representation."
  [inverted-index]
  (when (seq inverted-index)
    (let [words (for [[word positions] inverted-index
                      pos positions]
                  [pos (name word)])
          sorted-words (sort-by first words)]
      (string/join " " (map second sorted-words)))))


(defn search-openalex
  "Search papers on OpenAlex using the provided query."
  [query]
  (try
    (log/info "Searching OpenAlex for query:" query)
    (let [url "https://api.openalex.org/works"
          resp (http/get url {:headers default-headers
                              :query-params {:search query
                                             :per_page 10}})
          body (safe-json-parse (:body resp))
          results (map (fn [item]
                         {:source "OpenAlex"
                          :id (:id item)
                          :title (:title item)
                          :doi (:doi item)
                          :year (:publication_year item)
                          :authors (map #(get-in % [:author :display_name]) (:authorships item))
                          :venue (get-in item [:primary_location :source :display_name])
                          :abstract (reconstruct-abstract (:abstract_inverted_index item))
                          :citation-count (:cited_by_count item)
                          :is-oa (get-in item [:open_access :is_oa])
                          :oa-url (get-in item [:open_access :oa_url])})
                       (:results body))]
      (log/info "OpenAlex query returned" (count results) "results")
      results)
    (catch Exception e
      (log-error "OpenAlex search error:" (.getMessage e))
      [])))


(defn fetch-openalex-by-doi
  "Fetch paper metadata from OpenAlex by its DOI."
  [doi]
  (try
    (log/info "Fetching OpenAlex metadata for DOI:" doi)
    (let [clean-doi (string/replace doi #"https?://(dx\.)?doi\.org/" "")
          url (str "https://api.openalex.org/works/https://doi.org/" clean-doi)
          resp (http/get url {:headers default-headers})
          item (safe-json-parse (:body resp))]
      (if item
        (do
          (log/info "Found OpenAlex metadata for DOI:" doi)
          {:source "OpenAlex"
           :id (:id item)
           :title (:title item)
           :doi (:doi item)
           :year (:publication_year item)
           :authors (map #(get-in % [:author :display_name]) (:authorships item))
           :venue (get-in item [:primary_location :source :display_name])
           :abstract (reconstruct-abstract (:abstract_inverted_index item))
           :citation-count (:cited_by_count item)
           :is-oa (get-in item [:open_access :is_oa])
           :oa-url (get-in item [:open_access :oa_url])})
        (do
          (log/warn "No paper found in OpenAlex for DOI:" doi)
          nil)))
    (catch Exception e
      (log-error "OpenAlex fetch by DOI error:" (.getMessage e))
      nil)))


;; ==========================================
;; Semantic Scholar API
;; ==========================================

(defn search-semantic-scholar
  "Search papers on Semantic Scholar using the provided query."
  [query]
  (try
    (log/info "Searching Semantic Scholar for query:" query)
    (let [url "https://api.semanticscholar.org/graph/v1/paper/search"
          api-key (env-var "SEMANTIC_SCHOLAR_API_KEY")
          headers (if api-key
                    (assoc default-headers "x-api-key" api-key)
                    default-headers)
          resp (http/get url {:headers headers
                              :query-params {:query query
                                             :limit 10
                                             :fields "title,authors,year,externalIds,citationCount,abstract,url,venue"}})
          body (safe-json-parse (:body resp))
          results (map (fn [item]
                         {:source "Semantic Scholar"
                          :id (:paperId item)
                          :title (:title item)
                          :doi (get-in item [:externalIds :DOI])
                          :year (:year item)
                          :authors (map :name (:authors item))
                          :venue (:venue item)
                          :abstract (:abstract item)
                          :citation-count (:citationCount item)
                          :url (:url item)})
                       (:data body))]
      (log/info "Semantic Scholar query returned" (count results) "results")
      results)
    (catch Exception e
      (log-error "Semantic Scholar search error:" (.getMessage e))
      [])))


(defn fetch-semantic-scholar-recommendations
  "Fetch paper recommendations from Semantic Scholar for a given paper-id."
  [paper-id]
  (try
    (log/info "Fetching Semantic Scholar recommendations for paper ID:" paper-id)
    (let [url (str "https://api.semanticscholar.org/recommendations/v1/papers/forpaper/" paper-id)
          api-key (env-var "SEMANTIC_SCHOLAR_API_KEY")
          headers (if api-key
                    (assoc default-headers "x-api-key" api-key)
                    default-headers)
          resp (http/get url {:headers headers
                              :query-params {:limit 10
                                             :fields "title,authors,year,externalIds,citationCount,abstract,url,venue"}})
          body (safe-json-parse (:body resp))
          results (map (fn [item]
                         {:source "Semantic Scholar"
                          :id (:paperId item)
                          :title (:title item)
                          :doi (get-in item [:externalIds :DOI])
                          :year (:year item)
                          :authors (map :name (:authors item))
                          :venue (:venue item)
                          :abstract (:abstract item)
                          :citation-count (:citationCount item)
                          :url (:url item)})
                       (:recommendedPapers body))]
      (log/info "Semantic Scholar recommendations returned" (count results) "results")
      results)
    (catch Exception e
      (log-error "Semantic Scholar recommendations error:" (.getMessage e))
      [])))


;; ==========================================
;; Crossref API
;; ==========================================

(defn search-crossref
  "Search papers on Crossref using the provided query."
  [query]
  (try
    (log/info "Searching Crossref for query:" query)
    (let [url "https://api.crossref.org/works"
          resp (http/get url {:headers default-headers
                              :query-params {:query query
                                             :rows 10}})
          body (safe-json-parse (:body resp))
          results (map (fn [item]
                         {:source "Crossref"
                          :id (:DOI item)
                          :title (first (:title item))
                          :doi (:DOI item)
                          :year (get-in item [:created :date-parts 0 0])
                          :authors (map (fn [author]
                                          (str (:given author) " " (:family author)))
                                        (:author item))
                          :venue (first (:container-title item))
                          :citation-count (:is-referenced-by-count item)})
                       (get-in body [:message :items]))]
      (log/info "Crossref query returned" (count results) "results")
      results)
    (catch Exception e
      (log-error "Crossref search error:" (.getMessage e))
      [])))


(defn fetch-citation-format
  "Resolve the citation details of a DOI in a specific citation format MIME type."
  [doi format-mime]
  (try
    (log/info "Fetching citation format" format-mime "for DOI:" doi)
    (let [clean-doi (string/replace doi #"https?://(dx\.)?doi\.org/" "")
          url (str "https://doi.org/" clean-doi)
          resp (http/get url {:headers (assoc default-headers "Accept" format-mime)
                              :follow-redirects true})]
      (:body resp))
    (catch Exception e
      (log/error e "Error fetching citation format")
      (str "Error fetching citation: " (.getMessage e)))))


;; ==========================================
;; arXiv API
;; ==========================================

(defn extract-child-content
  "Helper to extract content of a specific tag from a parsed XML entry."
  [entry tag-name]
  (first (mapcat :content (filter #(= (:tag %) tag-name) (:content entry)))))


(defn extract-authors
  "Helper to extract list of author names from a parsed arXiv XML entry."
  [entry]
  (let [authors (filter #(= (:tag %) :author) (:content entry))]
    (map (fn [a] (first (mapcat :content (filter #(= (:tag %) :name) (:content a))))) authors)))


(defn parse-arxiv-xml
  "Parse arXiv XML response string into tag maps."
  [xml-str]
  (try
    (let [parsed (xml/parse (ByteArrayInputStream. (.getBytes xml-str "UTF-8")))]
      (filter #(= (:tag %) :entry) (:content parsed)))
    (catch Exception e
      (log-error "Error parsing arXiv XML:" (.getMessage e))
      [])))


(defn search-arxiv
  "Search papers on arXiv using the export query API."
  [query]
  (try
    (log/info "Searching arXiv for query:" query)
    (let [url "https://export.arxiv.org/api/query"
          resp (http/get url {:headers default-headers
                              :query-params {:search_query (str "all:" query)
                                             :max_results 10}})
          entries (parse-arxiv-xml (:body resp))
          results (map (fn [entry]
                         (let [id (extract-child-content entry :id)
                               title (extract-child-content entry :title)
                               summary (extract-child-content entry :summary)
                               published (extract-child-content entry :published)
                               year (when published (subs published 0 4))]
                           {:source "arXiv"
                            :id id
                            :title (when title (string/trim (string/replace title #"\n" " ")))
                            :doi nil
                            :year (when year (Integer/parseInt year))
                            :authors (extract-authors entry)
                            :venue "arXiv"
                            :abstract (when summary (string/trim (string/replace summary #"\n" " ")))
                            :citation-count 0
                            :url id
                            :is-oa true
                            :oa-url id}))
                       entries)]
      (log/info "arXiv query returned" (count results) "results")
      results)
    (catch Exception e
      (log-error "arXiv search error:" (.getMessage e))
      [])))


;; ==========================================
;; PubMed API
;; ==========================================

(defn search-pubmed
  "Search papers on PubMed using esearch and esummary APIs."
  [query]
  (try
    (log/info "Searching PubMed for query:" query)
    (let [search-url "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi"
          search-resp (http/get search-url {:headers default-headers
                                            :query-params {:db "pubmed"
                                                           :term query
                                                           :retmode "json"
                                                           :retmax 10}})
          search-body (safe-json-parse (:body search-resp))
          ids (get-in search-body [:esearchresult :idlist])]
      (if (seq ids)
        (let [summary-url "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi"
              summary-resp (http/get summary-url {:headers default-headers
                                                  :query-params {:db "pubmed"
                                                                 :id (string/join "," ids)
                                                                 :retmode "json"}})
              summary-body (safe-json-parse (:body summary-resp))
              results (get-in summary-body [:result])
              parsed (keep (fn [id]
                             (let [doc (get results (keyword id))]
                               (when doc
                                 {:source "PubMed"
                                  :id id
                                  :title (:title doc)
                                  :doi (first (keep (fn [articleid]
                                                      (when (= (:idtype articleid) "doi")
                                                        (:value articleid)))
                                                    (:articleids doc)))
                                  :year (when (:pubdate doc) (subs (:pubdate doc) 0 4))
                                  :authors (map :name (:authors doc))
                                  :venue (:source doc)
                                  :abstract nil ; esummary does not contain full abstract
                                  :citation-count 0
                                  :url (str "https://pubmed.ncbi.nlm.nih.gov/" id "/")})))
                           ids)]
          (log/info "PubMed query returned" (count parsed) "results")
          parsed)
        (do
          (log/info "PubMed query returned 0 results")
          [])))
    (catch Exception e
      (log-error "PubMed search error:" (.getMessage e))
      [])))


;; ==========================================
;; Springer Nature API
;; ==========================================

(defn search-springer
  "Search papers on Springer Nature metadata API (requires SPRINGER_API_KEY)."
  [query]
  (if-let [api-key (env-var "SPRINGER_API_KEY")]
    (try
      (log/info "Searching Springer Nature for query:" query)
      (let [url "https://api.springernature.com/metadata/v1/json"
            resp (http/get url {:headers default-headers
                                :query-params {:q query
                                               :api_key api-key
                                               :p 10}})
            body (safe-json-parse (:body resp))
            results (map (fn [item]
                           {:source "Springer Nature"
                            :id (:doi item)
                            :title (:title item)
                            :doi (:doi item)
                            :year (when (:publicationDate item) (subs (:publicationDate item) 0 4))
                            :authors (map :creator (:creators item))
                            :venue (:publicationName item)
                            :abstract (:abstract item)
                            :citation-count 0
                            :is-oa (= (:openaccess item) "true")})
                         (:records body))]
        (log/info "Springer Nature query returned" (count results) "results")
        results)
      (catch Exception e
        (log-error "Springer search error:" (.getMessage e))
        []))
    (do
      (log/debug "Springer Nature search skipped: SPRINGER_API_KEY is not configured")
      [])))


;; ==========================================
;; Paper PDF/HTML Extraction & Summarization
;; ==========================================

(defn extract-pdf-text
  "Extract text content from raw PDF bytes using Apache PDFBox."
  [bytes]
  (try
    (log/info "Extracting text from PDF document using PDFBox")
    (with-open [doc (PDDocument/load bytes)]
      (let [stripper (PDFTextStripper.)]
        (.getText stripper doc)))
    (catch Exception e
      (log-error "Failed to parse PDF using PDFBox:" (.getMessage e))
      (throw e))))


(defn extract-html-text
  "Extract text content from HTML string using Jsoup."
  [html-str]
  (try
    (log/info "Extracting text from HTML document using JSoup")
    (let [doc (Jsoup/parse html-str)]
      (-> doc .body .text))
    (catch Exception e
      (log-error "Failed to parse HTML using JSoup:" (.getMessage e))
      (throw e))))


(defn download-and-extract-paper
  "Download a paper from URL (PDF or HTML) and extract its plain text content."
  [url]
  (try
    (log/info "Downloading paper from URL:" url)
    (let [resp (http/get url {:headers default-headers
                              :as :byte-array
                              :conn-timeout 15000
                              :socket-timeout 15000
                              :follow-redirects true})
          content-type (get-in resp [:headers "content-type"] "")
          content-type-lower (string/lower-case (or content-type ""))
          bytes (:body resp)]
      (cond
        (or (string/includes? content-type-lower "application/pdf")
            (string/ends-with? (string/lower-case url) ".pdf"))
        (do
          (log/info "Detected PDF content-type or URL pattern")
          (extract-pdf-text bytes))

        :else
        (do
          (log/info "Detected HTML content-type")
          (extract-html-text (String. bytes "UTF-8")))))
    (catch Exception e
      (log-error "Error downloading/extracting paper from" url ":" (.getMessage e))
      (throw e))))


(defn summarize-text-fallback
  "Generate a local fallback layout summary from raw text if no LLM key is configured."
  [text]
  (log/info "Generating fallback structural summary (no LLM keys configured)")
  (let [lines (string/split-lines text)
        clean-lines (map string/trim lines)
        head (subs text 0 (min (count text) 6000))
        tail (if (> (count text) 6000)
               (subs text (- (count text) 4000))
               "")
        headings (->> clean-lines
                      (filter (fn [line]
                                (and (not (string/blank? line))
                                     (< (count line) 60)
                                     (re-matches #"^(?:[0-9]+\.?[0-9]*\s+[A-Z].*|[A-Z][A-Z0-9\s,\.\-\:]+)$" line))))
                      (take 25))]
    (str "## Fallback Document Extraction (No LLM Key Configured)\n\n"
         "Configure `GEMINI_API_KEY`, `OPENAI_API_KEY`, or `ANTHROPIC_API_KEY` in the environment to get automated high-quality summary reports on the server.\n\n"
         "### Document Structure / Section Headings Found:\n"
         (if (empty? headings)
           "* No clear heading patterns found.\n"
           (string/join "\n" (map #(str "* " %) headings)))
         "\n\n### Paper Excerpt (First 6,000 characters):\n```text\n"
         head
         "\n```\n\n"
         (when (not (string/blank? tail))
           (str "### Paper Excerpt (Last 4,000 characters):\n```text\n"
                tail
                "\n```\n")))))
