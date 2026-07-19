(defproject org.clojars.punit-naik/scholarly-mcp "0.1.0"
  :description "MCP Server for Academic Research & Literature Review"
  :url "https://github.com/punit-naik/scholarly-mcp"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [io.github.plumce/plumcp.core-json-cheshire "0.2.0"]
                 [clj-http "3.13.1"]
                 [cheshire "6.2.0"]
                 [org.apache.lucene/lucene-analysis-common "9.12.0"]]
  :main scholarly-mcp.core
  :uberjar-name "scholarly-mcp.jar"
  :profiles {:uberjar {:aot :all}})
