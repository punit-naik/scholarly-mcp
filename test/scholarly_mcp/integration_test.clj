(ns scholarly-mcp.integration-test
  (:require
    [cheshire.core :as json]
    [clojure.string :as string]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.io
      BufferedReader
      InputStreamReader
      OutputStreamWriter
      PrintWriter)
    (java.lang
      ProcessBuilder)
    (java.net
      ServerSocket)))


(defn start-mock-http-server
  "Starts a simple, single-use TCP server that acts as a mock HTTP server on an ephemeral port."
  []
  (let [server (ServerSocket. 0)
        port (.getLocalPort server)]
    (future
      (try
        (with-open [socket (.accept server)
                    out (PrintWriter. (.getOutputStream socket))]
          (.print out "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html><body>Mock Integration Test Paper Content</body></html>")
          (.flush out))
        (catch Exception _e)
        (finally
          (.close server))))
    port))


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
            (is (contains? tool-names "compare_papers"))
            (is (contains? tool-names "summarize_paper"))))

        ;; 3. Send JSON-RPC tools/call for 'summarize_paper'
        (let [port (start-mock-http-server)
              call-req {:jsonrpc "2.0"
                        :id 3
                        :method "tools/call"
                        :params {:name "summarize_paper"
                                 :arguments {:url (str "http://localhost:" port "/paper.html")}}}
              req-str (str (json/generate-string call-req) "\n")]
          (.write writer req-str)
          (.flush writer)

          ;; Read response line
          (let [res-str (.readLine reader)
                res (json/parse-string res-str true)
                content (get-in res [:result :content])
                text (get-in (first content) [:text])]
            (is (= "2.0" (:jsonrpc res)))
            (is (= 3 (:id res)))
            (is (seq content))
            (is (or (string/includes? text "Mock Integration Test Paper Content")
                    ;; Or if an LLM api key was present in the environment running the test, the LLM summary
                    (string/includes? text "Mock")
                    (string/includes? text "Paper")))))

        (finally
          ;; Clean up process
          (.destroy process))))))
