(ns scholarly-mcp.integration-test
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.io
      BufferedReader
      InputStreamReader
      OutputStreamWriter)
    (java.lang
      ProcessBuilder)))


(deftest stdio-integration-test
  (testing "Starts MCP server process and verifies JSON-RPC initialize and tools/list protocol exchange"
    (let [;; Start the server using 'lein run'
          pb (ProcessBuilder. ["lein" "run"])
          process (.start pb)
          reader (BufferedReader. (InputStreamReader. (.getInputStream process)))
          writer (OutputStreamWriter. (.getOutputStream process))]
      (try
        ;; 1. Send JSON-RPC 'initialize' request
        (let [init-req {:jsonrpc "2.0"
                        :id 1
                        :method "initialize"
                        :params {:protocolVersion "2025-11-25"
                                 :capabilities {}
                                 :clientInfo {:name "IntegrationTestClient" :version "1.0.0"}}}
              req-str (str (json/generate-string init-req) "\n")]
          (.write writer req-str)
          (.flush writer)

          ;; Read response line
          (let [res-str (.readLine reader)
                res (json/parse-string res-str true)]
            (is (= "2.0" (:jsonrpc res)))
            (is (= 1 (:id res)))
            (is (map? (:result res)))
            (is (get-in res [:result :protocolVersion]))
            (is (= "Scholarly MCP Server" (get-in res [:result :serverInfo :name])))))

        ;; 2. Send JSON-RPC 'tools/list' request
        (let [list-req {:jsonrpc "2.0"
                        :id 2
                        :method "tools/list"
                        :params {}}
              req-str (str (json/generate-string list-req) "\n")]
          (.write writer req-str)
          (.flush writer)

          ;; Read response line
          (let [res-str (.readLine reader)
                res (json/parse-string res-str true)
                tools (get-in res [:result :tools])
                tool-names (set (map :name tools))]
            (is (= "2.0" (:jsonrpc res)))
            (is (= 2 (:id res)))
            (is (seq tools))
            ;; Verify our key tools are listed
            (is (contains? tool-names "search_papers"))
            (is (contains? tool-names "find_nature_papers_by_abstract"))
            (is (contains? tool-names "compare_papers"))))

        (finally
          ;; Clean up process
          (.destroy process))))))
