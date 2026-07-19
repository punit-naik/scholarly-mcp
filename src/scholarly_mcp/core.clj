(ns scholarly-mcp.core
  (:gen-class)
  (:require
    [plumcp.core.api.entity-support :as es]
    [plumcp.core.api.mcp-server :as ms]
    [scholarly-mcp.tools]))


(def info (es/make-info "Scholarly MCP Server" "0.1.0"))


(defn -main
  [& _args]
  (binding [*out* *err*]
    (println "Starting Scholarly MCP Server on stdio..."))
  (ms/run-server {:info info
                  :transport :stdio
                  :vars (vals (ns-publics 'scholarly-mcp.tools))}))
