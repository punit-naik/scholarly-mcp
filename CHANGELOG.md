# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.1] - 2026-07-19

### Fixed
- Downgraded `plumcp` to `0.2.0` and `lucene` to `9.12.0` to preserve compatibility with Java 11 and Java 17 (resolves compilation error in older Java environments due to Java 21+ Virtual Threads usage in newer library versions).
- Updated CircleCI Docker image to next-gen `cimg/clojure:1.11` running OpenJDK 17.0.

## [0.1.0] - 2026-07-19

### Added
- Renamed project from `research-mcp` to `scholarly-mcp` and published to coordinates `org.clojars.punit-naik/scholarly-mcp`.
- Integrated 6 major academic data providers: OpenAlex, Crossref, Semantic Scholar, Springer Nature, arXiv, and PubMed.
- Added 7 MCP tools: `search_papers`, `get_citations`, `retrieve_full_text`, `compare_papers`, `build_literature_review`, `export_citations`, and `find_nature_papers_by_abstract`.
- Created automated integration and unit test suite verifying all tools and API endpoints.
- Added Git pre-commit webhook formatting validation using `cljstyle`.
- Configured CI pipeline with next-gen CircleCI image `cimg/clojure:1.11` running OpenJDK 17.0.
- Created `LICENSE` file under the Eclipse Public License 2.0 (EPL-2.0).

