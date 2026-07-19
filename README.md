# Scholarly MCP Server

[![CircleCI](https://circleci.com/gh/punit-naik/scholarly-mcp/tree/main.svg?style=svg)](https://circleci.com/gh/punit-naik/scholarly-mcp/tree/main)
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.punit-naik/scholarly-mcp.svg)](https://clojars.org/org.clojars.punit-naik/scholarly-mcp)

A Clojure-based Model Context Protocol (MCP) server powered by the [plumcp](https://github.com/plumce/plumcp) framework. It aggregates data from academic research databases (OpenAlex, Crossref, Semantic Scholar, Springer Nature, arXiv, and PubMed) to provide high-level research tools for LLM clients. Available on Clojars as `org.clojars.punit-naik/scholarly-mcp`.

## Features & Tools

The server exposes the following MCP tools to help AI assistants search, analyze, and synthesize literature:

1. `search_papers`: Aggregates and deduplicates searches across all 6 databases.
2. `get_citations`: Fetches works citing a specific paper by DOI (via OpenAlex).
3. `retrieve_full_text`: Looks up metadata and retrieves Open Access (OA) download links.
4. `compare_papers`: Generates a side-by-side Markdown comparison table and abstracts summary for multiple DOIs.
5. `build_literature_review`: Synthesizes search results, grouping them by publication venue.
6. `export_citations`: Retrieves formatted references in BibTeX or RIS formats.
7. `find_nature_papers_by_abstract`: Recommends related Nature journal publications based on keywords extracted from an abstract.
8. `summarize_paper`: Reuses `retrieve_full_text` to locate open-access PDFs/HTML, extracting full-text content and generating detailed summaries via the client's native sampling capability or server-side API keys (falling back to structured excerpts if no keys are configured).

---

## Practical Applications

This MCP server enables AI assistants (like Claude, ChatGPT, or custom agents) to act as autonomous research partners. Here are some key real-world use cases:

* **Automated Literature Reviews**: Instead of manually searching, copying, and organizing papers from multiple index portals, users can ask an AI agent to:
  > *"Find the top 15 papers on 'RAG evaluation' published since 2023, deduplicate them, and organize them into a literature review grouped by journal/venue."*
  The agent will use `build_literature_review` to compile a clean, structured synthesis report.

* **Quick Cross-Study Comparisons**: Researchers often need to compare findings, methodologies, or sample sizes across multiple papers. You can tell the AI:
  > *"Compare the papers with DOIs 10.1038/s41586-024-01641-0 and 10.1145/3703155 side-by-side."*
  The agent will use `compare_papers` to generate a structured markdown matrix comparing authors, year, citation counts, venues, and abstracts side-by-side.

* **Citation Traceability & Impact Mapping**: When reviewing a breakthrough paper, researchers need to find how it was built upon. You can ask:
  > *"What are the most cited papers that cite the original LLM hallucination survey (DOI: 10.1145/3703155)?"*
  The agent will use `get_citations` to fetch citing works, allowing the AI to trace the academic lineage and construct a citation tree.

* **Frictionless Open Access PDF Retrieval**: Instead of hitting paywalls or hunting for free PDFs manually, the AI can resolve access options instantly:
  > *"Retrieve the metadata and check if there is an open access PDF download link available for DOI 10.1038/s41746-025-01670-7."*
  The agent uses `retrieve_full_text` to locate valid public-access URLs and PDF downloads immediately.

* **Auto-Generating Reference Bibliographies**: When drafting a paper, users can ask:
  > *"Give me the BibTeX citations for the following three DOIs so I can add them to my LaTeX bibliography."*
  The agent uses `export_citations` to fetch clean, registry-validated RIS or BibTeX records directly from Crossref/DOI resolvers, preventing formatting errors.

* **Abstract-Based Journal Recommendations**: Authors looking for a publication venue can feed a draft abstract to the AI:
  > *"Based on my draft abstract, find similar papers published in Nature journals to help me decide where to submit."*
  The agent uses `find_nature_papers_by_abstract` (powered by local Apache Lucene keyword extraction) to recommend matching publications.

* **Detailed Full-Text Paper Summaries**: When researchers need a deep summary of a paper's full text:
  > *"Download and summarize the paper with DOI 10.1145/3703155"*
  The agent will reuse `retrieve_full_text` to find the open access URL, download the PDF/HTML using `summarize_paper`, parse its contents with PDFBox or JSoup, and generate a detailed structured summary (highlighting core contribution, methodology, findings, and limitations) leveraging client-side LLM sampling or server fallbacks.

---

## Design Philosophy & Token Efficiency

To ensure the server is fast, cheap, and lightweight for LLM clients, we adhere to the following principles:

- **Algorithm-First Over LLM-First**: We use deterministic local algorithms and direct REST APIs to process data rather than relying on LLM reasoning (e.g., keyword extraction in `find_nature_papers_by_abstract` is done entirely in Clojure using word-frequency analysis rather than asking the LLM to identify keywords).
- **Minimal Token Usage**: Returned payloads are concise, aggressively deduplicated, and formatted directly into compact Markdown structures to prevent context window bloat.
- **Leverage Standard Tools/Libraries**: We use established libraries for HTTP operations, parsing, and data formats instead of delegating formatting tasks to the LLM (e.g., retrieving BibTeX/RIS formatting directly from the DOI registry using HTTP content negotiation).

---

## Prerequisites

- **Java JDK** (version 11 or higher recommended)
- **Leiningen** (Clojure build tool)

---

## API Keys & Account Setup

To get the most out of the Research MCP Server, you should configure API keys for the academic indexes that require or benefit from authentication.

### 1. Springer Nature API (`SPRINGER_API_KEY`)
* **Status**: **Required for Springer Nature search**. If not provided, search requests to Springer Nature will be gracefully skipped (returning an empty list) without crashing.
* **How to Get It**:
  1. Go to the [Springer Nature Developer Portal](https://dev.springernature.com/).
  2. Register for a free developer account.
  3. Create an application/project to obtain an **API Key**. Note that Springer Nature provides multiple key types (e.g., *Open Access API* and *Metadata API*). You must use the **Metadata API key** (Meta API) for this server.
* **Environment Variable**: `SPRINGER_API_KEY`

### 2. Semantic Scholar API (`SEMANTIC_SCHOLAR_API_KEY`)
* **Status**: **Optional**. The server works without an API key using the public endpoint, but requests will be subject to lower rate limits.
* **How to Get It**:
  1. Go to the [Semantic Scholar API Page](https://www.semanticscholar.org/product/api).
  2. Request an API key (either a free/academic tier or business tier key depending on your use case).
* **Environment Variable**: `SEMANTIC_SCHOLAR_API_KEY`

### 3. OpenAlex, Crossref, arXiv, and PubMed
* **Status**: **No API key or account required**. These services use public, open-access endpoints. The server sends polite User-Agent headers (with a contact email) to ensure good performance and compliance with their rate-limiting and courtesy pool guidelines.

---

## Testing

There are two ways to test the server: automated unit tests and manual/interactive testing.

### 1. Automated Unit Tests
To run the automated Clojure unit tests (which do not require network access or API keys):
```bash
lein test
```

### 2. Interactive Testing (via MCP Inspector)
The easiest way to test the MCP server tools manually is using the official **MCP Inspector**. This launches a web-based UI where you can interactively invoke tools, see the JSON-RPC traffic, and inspect tool outputs.

Make sure you have Node.js/npx installed, then run:

#### Testing via Leiningen (Development)
```bash
SPRINGER_API_KEY="your_key" SEMANTIC_SCHOLAR_API_KEY="your_key" npx @modelcontextprotocol/inspector lein run
```

#### Testing via Standalone JAR
1. Compile the project first:
   ```bash
   lein uberjar
   ```
2. Run the inspector pointing to the compiled JAR:
   ```bash
   SPRINGER_API_KEY="your_key" SEMANTIC_SCHOLAR_API_KEY="your_key" npx @modelcontextprotocol/inspector java -jar target/scholarly-mcp.jar
   ```

Open the URL printed in the console (usually `http://localhost:5173`) in your browser to test the tools (`search_papers`, `get_citations`, etc.).

---

## Development & Code Style

To maintain a consistent and clean codebase, this project uses `cljstyle` for formatting.

### Automated Checks
* **On Commit**: A Git pre-commit hook automatically runs `cljstyle check` on every commit. If any Clojure file does not comply with the formatting rules, the commit will be blocked until the formatting is resolved.

---

## Installation & Configuration in AI Clients

The server communicates via standard input/output (stdio). You can install it in any MCP-compatible IDE or client.

### A. Claude Desktop
Add the server configuration to your `claude_desktop_config.json` file.

#### File Locations:
- **Linux:** `~/.config/Claude/claude_desktop_config.json`
- **macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows:** `%APPDATA%\Claude\claude_desktop_config.json`

#### Config Example (Using Leiningen directly):
```json
{
  "mcpServers": {
    "scholarly-mcp": {
      "command": "lein",
      "args": ["run"],
      "env": {
        "SPRINGER_API_KEY": "YOUR_SPRINGER_API_KEY",
        "SEMANTIC_SCHOLAR_API_KEY": "YOUR_SEMANTIC_SCHOLAR_API_KEY"
      },
      "cwd": "/path/to/scholarly-mcp"
    }
  }
}
```

#### Config Example (Using Compiled Standalone JAR - Recommended for performance):
Ensure you run `lein uberjar` first to compile the JAR.
```json
{
  "mcpServers": {
    "scholarly-mcp": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/scholarly-mcp/target/scholarly-mcp.jar"
      ],
      "env": {
        "SPRINGER_API_KEY": "YOUR_SPRINGER_API_KEY",
        "SEMANTIC_SCHOLAR_API_KEY": "YOUR_SEMANTIC_SCHOLAR_API_KEY"
      }
    }
  }
}
```

### B. Cursor
To configure the server in Cursor:
1. Go to **Cursor Settings** -> **Features** -> **MCP**.
2. Click **+ Add New MCP Server**.
3. Fill in the configuration:
   - **Name:** `ScholarlyMCP`
   - **Type:** `command`
   - **Command:**
     ```bash
     java -jar /path/to/scholarly-mcp/target/scholarly-mcp.jar
     ```
4. Set Environment Variables for API Keys in the environment configuration of Cursor or your shell before launching Cursor:
   ```bash
   export SPRINGER_API_KEY="your_key"
   export SEMANTIC_SCHOLAR_API_KEY="your_key"
   ```

---

## Programmatic Integration Examples

In addition to using the server inside interactive AI chats (like Claude Desktop or Cursor), you can build wrapper scripts or automated workflows that talk to this MCP server over stdio.

### 1. Python Automation Example
This script launches the standalone JAR, performs the JSON-RPC initialization handshake, and retrieves search results programmatically:

```python
import subprocess
import json
import os

# Ensure the standalone JAR is compiled using `lein uberjar`
jar_path = "./target/scholarly-mcp.jar"

# Spawn the MCP server process
proc = subprocess.Popen(
    ["java", "-jar", jar_path],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.DEVNULL,
    text=True,
    env=os.environ
)

def send_rpc(method, params, request_id):
    payload = {
        "jsonrpc": "2.0",
        "id": request_id,
        "method": method,
        "params": params
    }
    proc.stdin.write(json.dumps(payload) + "\n")
    proc.stdin.flush()
    
    # Read the single line JSON-RPC response
    line = proc.stdout.readline()
    return json.loads(line)

try:
    # Step 1: Send protocol handshake (initialize)
    init_res = send_rpc("initialize", {
        "protocolVersion": "2025-11-25",
        "capabilities": {},
        "clientInfo": {"name": "PythonClient", "version": "1.0"}
    }, 1)
    print(f"Connected to: {init_res['result']['serverInfo']['name']} v{init_res['result']['serverInfo']['version']}\n")

    # Step 2: Query for papers
    query = "llm hallucination"
    response = send_rpc("tools/call", {
        "name": "search_papers",
        "arguments": {"query": query}
    }, 2)

    # Print the resulting markdown report
    markdown_report = response["result"]["content"][0]["text"]
    print(markdown_report)

finally:
    # Step 3: Clean up and close process
    proc.terminate()
```

### 2. Node.js CLI Script Example
This Node.js script launches the server and prints the list of available tools:

```javascript
const { spawn } = require('child_process');

const jarPath = './target/scholarly-mcp.jar';
const server = spawn('java', ['-jar', jarPath]);

let buffer = '';

server.stdout.on('data', (data) => {
  buffer += data.toString();
  const lines = buffer.split('\n');
  buffer = lines.pop(); // Hold incomplete lines

  for (const line of lines) {
    if (line.trim()) {
      const response = JSON.parse(line);
      if (response.id === 1) {
        // Initialized! Now request the tools list.
        sendRpc('tools/list', {}, 2);
      } else if (response.id === 2) {
        // Print available tools
        console.log('Registered Tools:');
        response.result.tools.forEach(tool => {
          console.log(`- ${tool.name}: ${tool.description}`);
        });
        server.kill();
        process.exit(0);
      }
    }
  }
});

function sendRpc(method, params, id) {
  const req = { jsonrpc: '2.0', id, method, params };
  server.stdin.write(JSON.stringify(req) + '\n');
}

// Handshake
sendRpc('initialize', {
  protocolVersion: '2025-11-25',
  capabilities: {},
  clientInfo: { name: 'NodeClient', version: '1.0' }
}, 1);
```

---

## Acknowledgements

Special thanks to the creators of the [plumcp](https://github.com/plumce/plumcp) library, which made building this Model Context Protocol server in Clojure simple, lightweight, and robust.

---

## License

Copyright © 2026 [Punit Naik](https://github.com/punit-naik)

This program and the accompanying materials are made available under the terms of the Eclipse Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary Licenses when the conditions for such availability set forth in the Eclipse Public License, v. 2.0 are satisfied: GNU General Public License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any later version, with the GNU Classpath Exception which is available at https://www.gnu.org/software/classpath/license.html.
