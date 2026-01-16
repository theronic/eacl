# EACL Project

## Clojure MCP Server (clojure-mcp-eacl)

This project uses the `clojure-mcp-eacl` MCP server for file operations and REPL evaluation. **Always prefer MCP tools over built-in tools** for reading, editing, and creating files.

### Starting the MCP Server

The MCP server is configured to start automatically when Claude Code launches. If it's not running:

1. Restart Claude Code, or
2. Run manually: `clojure -Tmcp start :config-profile :cli-assist :port 7889`

The `:mcp` alias is defined in `~/.clojure/deps.edn`.

### Available MCP Tools

Use these tools instead of built-in equivalents:

| Task | Use MCP Tool | NOT |
|------|-------------|-----|
| Read file | `mcp__clojure-mcp-eacl__file_read` | `Read` tool |
| Edit file | `mcp__clojure-mcp-eacl__file_edit` | `Edit` tool |
| Create file | `mcp__clojure-mcp-eacl__file_write` | `Write` tool |
| List files | `mcp__clojure-mcp-eacl__list_directory` | `Bash(ls)` |
| Find files | `mcp__clojure-mcp-eacl__glob_files` | `Glob` tool |
| Search content | `mcp__clojure-mcp-eacl__grep_search` | `Grep` tool |
| Evaluate Clojure | `mcp__clojure-mcp-eacl__eval` | `Bash(clj-nrepl-eval)` |

### Clojure REPL Evaluation

The MCP server maintains a persistent nREPL session. Use `mcp__clojure-mcp-eacl__eval` for:

- Evaluating Clojure expressions
- Requiring namespaces (use `:reload` to pick up changes)
- Running tests
- Inspecting data structures

Example workflow:
```clojure
;; Require namespace with reload
(require '[eacl.core :as eacl] :reload)

;; Evaluate expressions
(eacl/some-function arg1 arg2)

;; Run tests
(require '[clojure.test :refer [run-tests]])
(run-tests 'eacl.core-test)
```

### Troubleshooting

If MCP tools are unavailable:
1. Check if server is running: look for `clojure-mcp-eacl` in MCP server list
2. Restart Claude Code to reconnect
3. Port 7889 must be available
