# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.2] - 2026-07-19

### Fixed
- Updated CircleCI Docker image to next-gen `cimg/clojure:1.11-openjdk-21.0` (OpenJDK 21) to satisfy Java 21 Virtual Threads compilation requirements from the `plumcp` library.

## [0.1.1] - 2026-07-19

### Changed
- Upgraded dependencies to latest stable versions (`plumcp` to `0.2.2` and `lucene` to `10.5.0`), establishing Java 21 as a baseline requirement due to downstream Virtual Thread support in the web server layer.

## [0.1.0] - 2026-07-19

### Added
- Renamed project from `research-mcp` to `scholarly-mcp` and published to coordinates `org.clojars.punit-naik/scholarly-mcp`.
- Integrated 6 major academic data providers: OpenAlex, Crossref, Semantic Scholar, Springer Nature, arXiv, and PubMed.
- Added 7 MCP tools: `search_papers`, `get_citations`, `retrieve_full_text`, `compare_papers`, `build_literature_review`, `export_citations`, and `find_nature_papers_by_abstract`.
- Created automated integration and unit test suite verifying all tools and API endpoints.
- Added Git pre-commit webhook formatting validation using `cljstyle`.
- Configured CI pipeline with next-gen CircleCI image running OpenJDK 21.0.
- Created `LICENSE` file under the Eclipse Public License 2.0 (EPL-2.0).

