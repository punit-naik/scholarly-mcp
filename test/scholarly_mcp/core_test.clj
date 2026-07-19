(ns scholarly-mcp.core-test
  (:require
    [clj-http.client :as http]
    [clojure.string :as string]
    [clojure.test :refer [is deftest testing]]
    [scholarly-mcp.api :as api]
    [scholarly-mcp.tools :refer [deduplicate-papers extract-keywords search-papers get-citations retrieve-full-text compare-papers export-citations find-nature-papers-by-abstract build-literature-review]]))


(deftest deduplication-test
  (testing "Deduplicating papers by DOI and title"
    (let [papers [{:title "A Great Paper" :doi "10.1234/abc" :citation-count 10}
                  {:title "a great paper" :doi "https://doi.org/10.1234/abc" :citation-count 20 :is-oa true :oa-url "http://oa.com"}
                  {:title "Another Paper" :doi nil}
                  {:title "another paper" :doi nil}]]
      (is (= 2 (count (deduplicate-papers papers))))
      (let [deduped (deduplicate-papers papers)
            first-paper (first (filter #(= (:doi %) "10.1234/abc") deduped))]
        (is (= 20 (:citation-count first-paper)))
        (is (:is-oa first-paper))))))


(deftest doi-cleanup-test
  (testing "DOI normalization"
    (is (= "10.1038/nature14539" (scholarly-mcp.tools/clean-doi "https://doi.org/10.1038/nature14539")))
    (is (= "10.1038/nature14539" (scholarly-mcp.tools/clean-doi "http://dx.doi.org/10.1038/nature14539")))
    (is (= "10.1038/nature14539" (scholarly-mcp.tools/clean-doi "10.1038/Nature14539")))))


(deftest abstract-reconstruction-test
  (testing "OpenAlex abstract inverted index reconstruction"
    (let [index {:The [0 5] :dog [1] :chased [2] :the [3] :cat [4]}]
      (is (= "The dog chased the cat The" (api/reconstruct-abstract index))))))


(deftest extract-keywords-test
  (testing "Lucene-based keyword extraction and stop word removal"
    (let [abstract "The quantum computer is a device that uses quantum mechanics to process information."
          keywords (extract-keywords abstract 3)
          kw-list (string/split keywords #" ")]
      (is (= 3 (count kw-list)))
      (is (string/includes? keywords "quantum"))
      (is (not (string/includes? keywords "the")))
      (is (not (string/includes? keywords "is")))
      (is (not (string/includes? keywords "that"))))))


(def mock-paper
  {:title "Mock Paper"
   :authors ["Alice" "Bob"]
   :year 2024
   :venue "Mock Nature Venue"
   :doi "10.1234/mock"
   :citation-count 42
   :is-oa true
   :oa-url "http://mock.com"
   :abstract "This is a mock paper abstract."})


(deftest tools-test
  (with-redefs [api/search-openalex (constantly [mock-paper])
                api/search-semantic-scholar (constantly [mock-paper])
                api/search-arxiv (constantly [mock-paper])
                api/search-pubmed (constantly [mock-paper])
                api/search-springer (constantly [mock-paper])
                api/fetch-openalex-by-doi (constantly mock-paper)
                api/fetch-citation-format (constantly "mock citation")
                http/get (constantly {:body "{\"results\": []}"})]

    (testing "search_papers tool"
      (let [res (search-papers {:query "test"})
            text (-> res :content first :text)]
        (is (string/includes? text "# Search Results for: test"))
        (is (string/includes? text "Mock Paper"))))

    (testing "get_citations tool"
      (let [res (get-citations {:doi "10.1234/mock"})
            text (-> res :content first :text)]
        (is (string/includes? text "Papers citing DOI"))))

    (testing "retrieve_full_text tool"
      (let [res (retrieve-full-text {:doi "10.1234/mock"})
            text (-> res :content first :text)]
        (is (string/includes? text "Paper Full-Text Lookup"))))

    (testing "compare_papers tool"
      (let [res (compare-papers {:dois "10.1234/mock,10.5678/mock"})
            text (-> res :content first :text)]
        (is (string/includes? text "Side-by-Side Paper Comparison"))))

    (testing "export_citations tool"
      (let [res (export-citations {:dois "10.1234/mock" :format "bibtex"})
            text (-> res :content first :text)]
        (is (string/includes? text "mock citation"))))

    (testing "find_nature_papers_by_abstract tool"
      (let [res (find-nature-papers-by-abstract {:abstract "some quantum computing abstract"})
            text (-> res :content first :text)]
        (is (string/includes? text "Related Nature Papers"))))

    (testing "build_literature_review tool"
      (let [res (build-literature-review {:query "test"})
            text (-> res :content first :text)]
        (is (string/includes? text "Literature Synthesis"))))))


(deftest api-additional-functions-test
  (testing "search-crossref api function"
    (with-redefs [http/get (constantly {:body "{\"message\": {\"items\": []}}"})]
      (is (empty? (api/search-crossref "test")))))

  (testing "fetch-semantic-scholar-recommendations api function"
    (with-redefs [http/get (constantly {:body "{\"recommendedPapers\": []}"})]
      (is (empty? (api/fetch-semantic-scholar-recommendations "test-id"))))))
