(ns scholarly-mcp.api
  (:require
    [cheshire.core :as json]
    [clj-http.client :as http]
    [clojure.string :as string]
    [clojure.xml :as xml])
  (:import
    (java.io
      ByteArrayInputStream)))


;; Polite headers for rate limit pooling
(def default-headers
  {"User-Agent" "ResearchMCP/0.1.0 (mailto:punit.naik@gmail.com; local development)"})


(defn log-error
  [& args]
  (binding [*out* *err*]
    (apply println args)))


(defn safe-json-parse
  [s]
  (try
    (json/parse-string s true)
    (catch Exception _
      nil)))


;; ==========================================
;; OpenAlex API
;; ==========================================

(defn reconstruct-abstract
  [inverted-index]
  (when (seq inverted-index)
    (let [words (for [[word positions] inverted-index
                      pos positions]
                  [pos (name word)])
          sorted-words (sort-by first words)]
      (string/join " " (map second sorted-words)))))


(defn search-openalex
  [query]
  (try
    (let [url "https://api.openalex.org/works"
          resp (http/get url {:headers default-headers
                              :query-params {:search query
                                             :per_page 10}})
          body (safe-json-parse (:body resp))]
      (map (fn [item]
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
           (:results body)))
    (catch Exception e
      (log-error "OpenAlex search error:" (.getMessage e))
      [])))


(defn fetch-openalex-by-doi
  [doi]
  (try
    (let [clean-doi (string/replace doi #"https?://(dx\.)?doi\.org/" "")
          url (str "https://api.openalex.org/works/https://doi.org/" clean-doi)
          resp (http/get url {:headers default-headers})
          item (safe-json-parse (:body resp))]
      (when item
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
         :oa-url (get-in item [:open_access :oa_url])}))
    (catch Exception e
      (log-error "OpenAlex fetch by DOI error:" (.getMessage e))
      nil)))


;; ==========================================
;; Semantic Scholar API
;; ==========================================

(defn search-semantic-scholar
  [query]
  (try
    (let [url "https://api.semanticscholar.org/graph/v1/paper/search"
          api-key (System/getenv "SEMANTIC_SCHOLAR_API_KEY")
          headers (if api-key
                    (assoc default-headers "x-api-key" api-key)
                    default-headers)
          resp (http/get url {:headers headers
                              :query-params {:query query
                                             :limit 10
                                             :fields "title,authors,year,externalIds,citationCount,abstract,url,venue"}})
          body (safe-json-parse (:body resp))]
      (map (fn [item]
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
           (:data body)))
    (catch Exception e
      (log-error "Semantic Scholar search error:" (.getMessage e))
      [])))


(defn fetch-semantic-scholar-recommendations
  [paper-id]
  (try
    (let [url (str "https://api.semanticscholar.org/recommendations/v1/papers/forpaper/" paper-id)
          api-key (System/getenv "SEMANTIC_SCHOLAR_API_KEY")
          headers (if api-key
                    (assoc default-headers "x-api-key" api-key)
                    default-headers)
          resp (http/get url {:headers headers
                              :query-params {:limit 10
                                             :fields "title,authors,year,externalIds,citationCount,abstract,url,venue"}})
          body (safe-json-parse (:body resp))]
      (map (fn [item]
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
           (:recommendedPapers body)))
    (catch Exception e
      (log-error "Semantic Scholar recommendations error:" (.getMessage e))
      [])))


;; ==========================================
;; Crossref API
;; ==========================================

(defn search-crossref
  [query]
  (try
    (let [url "https://api.crossref.org/works"
          resp (http/get url {:headers default-headers
                              :query-params {:query query
                                             :rows 10}})
          body (safe-json-parse (:body resp))]
      (map (fn [item]
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
           (get-in body [:message :items])))
    (catch Exception e
      (log-error "Crossref search error:" (.getMessage e))
      [])))


(defn fetch-citation-format
  [doi format-mime]
  (try
    (let [clean-doi (string/replace doi #"https?://(dx\.)?doi\.org/" "")
          url (str "https://doi.org/" clean-doi)
          resp (http/get url {:headers (assoc default-headers "Accept" format-mime)
                              :follow-redirects true})]
      (:body resp))
    (catch Exception e
      (str "Error fetching citation: " (.getMessage e)))))


;; ==========================================
;; arXiv API
;; ==========================================

(defn extract-child-content
  [entry tag-name]
  (first (mapcat :content (filter #(= (:tag %) tag-name) (:content entry)))))


(defn extract-authors
  [entry]
  (let [authors (filter #(= (:tag %) :author) (:content entry))]
    (map (fn [a] (first (mapcat :content (filter #(= (:tag %) :name) (:content a))))) authors)))


(defn parse-arxiv-xml
  [xml-str]
  (try
    (let [parsed (xml/parse (ByteArrayInputStream. (.getBytes xml-str "UTF-8")))]
      (filter #(= (:tag %) :entry) (:content parsed)))
    (catch Exception e
      (log-error "Error parsing arXiv XML:" (.getMessage e))
      [])))


(defn search-arxiv
  [query]
  (try
    (let [url "https://export.arxiv.org/api/query"
          resp (http/get url {:headers default-headers
                              :query-params {:search_query (str "all:" query)
                                             :max_results 10}})
          entries (parse-arxiv-xml (:body resp))]
      (map (fn [entry]
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
           entries))
    (catch Exception e
      (log-error "arXiv search error:" (.getMessage e))
      [])))


;; ==========================================
;; PubMed API
;; ==========================================

(defn search-pubmed
  [query]
  (try
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
              results (get-in summary-body [:result])]
          (keep (fn [id]
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
                ids))
        []))
    (catch Exception e
      (log-error "PubMed search error:" (.getMessage e))
      [])))


;; ==========================================
;; Springer Nature API
;; ==========================================

(defn search-springer
  [query]
  (if-let [api-key (System/getenv "SPRINGER_API_KEY")]
    (try
      (let [url "https://api.springernature.com/metadata/v1/json"
            resp (http/get url {:headers default-headers
                                :query-params {:q query
                                               :api_key api-key
                                               :p 10}})
            body (safe-json-parse (:body resp))]
        (map (fn [item]
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
             (:records body)))
      (catch Exception e
        (log-error "Springer search error:" (.getMessage e))
        []))
    []))
