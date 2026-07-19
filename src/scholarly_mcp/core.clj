(ns scholarly-mcp.core
  (:gen-class)
  (:require
    [clojure.tools.logging :as log]
    [plumcp.core.api.entity-support :as es]
    [plumcp.core.api.mcp-server :as ms]
    [scholarly-mcp.tools]))


(def info
  "Metadata information about the Scholarly MCP Server."
  (es/make-info "Scholarly MCP Server" "0.2.0"))


(defn -main
  "The main entry point for the Scholarly MCP Server. Starts the stdio transport server."
  [& _args]
  (binding [*out* *err*]
    (println "Starting Scholarly MCP Server on stdio..."))
  (log/info "Initializing Scholarly MCP Server and running stdio transport loop.")
  (ms/run-server {:info info
                  :transport :stdio
                  :vars (vals (ns-publics 'scholarly-mcp.tools))}))
