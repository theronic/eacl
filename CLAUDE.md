# EACL Project

## Clojure MCP Server (project-0-eacl-clojure-mcp)

This project uses the `project-0-eacl-clojure-mcp` MCP server for file operations and REPL evaluation. **Always prefer MCP tools over built-in tools** for reading, editing, and creating files.

### IMPORTANT: Starting nREPL Server First

**Before using any MCP REPL tools, you MUST have an nREPL server running in the EACL project directory.**

#### Quick Start: Check and Start nREPL

**Step 1: Check for existing nREPL server**

Use the MCP tool:
- Server: `project-0-eacl-clojure-mcp`
- Tool: `list_nrepl_ports`
- Arguments: `{"explanation": "Check if EACL nREPL is running"}`

Look for a server listed under "In current directory (/Users/petrus/Code/eacl)".

**Step 2: Start server if needed**

If no server is running in `/Users/petrus/Code/eacl`, use the **Shell tool** to start one:

```bash
cd /Users/petrus/Code/eacl && clojure -M:nrepl &
```

**Wait 3-5 seconds** for the JVM to start and the server to initialize.

**Step 3: Verify startup**

Run `list_nrepl_ports` again (MCP tool) to confirm the server is running and get the port number.

**CRITICAL**: Always use `clojure -M:nrepl`. Do NOT use `-M:dev:test` or `-M:test` - these aliases have conflicting main-opts that will cause startup to fail with "Unknown option: -m" error.

#### nREPL Alias Already Configured

The EACL `deps.edn` includes an `:nrepl` alias following [clojure-mcp best practices](https://github.com/bhauman/clojure-mcp/blob/main/doc/nrepl.md):

```clojure
:nrepl {:extra-paths ["test"]
        :extra-deps {nrepl/nrepl {:mvn/version "1.3.1"}}
        :main-opts ["-m" "nrepl.cmdline"]}
```

Start with: `clojure -M:nrepl` (no CIDER middleware needed for clojure-mcp)

### Available MCP Tools

Use these tools instead of built-in equivalents:

| Task | Use MCP Tool | NOT |
|------|-------------|-----|
| Read file | `read_file` (MCP) | `Read` tool |
| Edit file | `file_edit` (MCP) | `StrReplace` tool |
| Create file | `file_write` (MCP) | `Write` tool |
| List files | `list_directory` (MCP) | `LS` tool |
| Find files | `glob_files` (MCP) | `Glob` tool |
| Search content | `grep` (MCP) | `Grep` tool |
| Evaluate Clojure | `clojure_eval` (MCP) | `Shell(clojure -M:test ...)` |
| Run tests | `clojure_eval` with test code | Shell commands |
| List nREPL ports | `list_nrepl_ports` (MCP) | Manual checking |

### Clojure REPL Evaluation

The MCP `clojure_eval` tool connects to the running nREPL server. **You must specify the port** from `list_nrepl_ports` output.

#### Correct Usage Pattern:

**Step 1: Discover the port**

Use MCP tool `list_nrepl_ports`:
- Server: `project-0-eacl-clojure-mcp`
- Tool: `list_nrepl_ports`
- Arguments: `{"explanation": "Find EACL nREPL port"}`

Example output: `localhost:61717 (clj)` in `/Users/petrus/Code/eacl`

**Step 2: Evaluate code**

Use MCP tool `clojure_eval`:
- Server: `project-0-eacl-clojure-mcp`
- Tool: `clojure_eval`
- Arguments: `{"code": "(+ 1 2 3)", "port": 61717}`

Replace `61717` with the actual port from Step 1.

#### Running Tests via REPL (FAST):

Use MCP tool `clojure_eval`:
- Server: `project-0-eacl-clojure-mcp`
- Tool: `clojure_eval`
- Arguments:
```json
{
  "code": "(require '[clojure.test :refer [run-tests]])\n(require '[eacl.datomic.impl.indexed-test] :reload)\n(run-tests 'eacl.datomic.impl.indexed-test)",
  "port": 61717
}
```

**This is MUCH faster than running `clojure -M:test` via Shell, which restarts the JVM every time.**

#### Example Workflow:

```clojure
;; Require namespace with reload
(require '[eacl.datomic.impl.indexed :as indexed] :reload)

;; Evaluate expressions
(indexed/some-function arg1 arg2)

;; Run specific test namespace
(require '[clojure.test :refer [run-tests]])
(require '[eacl.datomic.impl.indexed-test] :reload)
(run-tests 'eacl.datomic.impl.indexed-test)

;; Run single test
(require '[clojure.test :refer [test-var]])
(test-var #'eacl.datomic.impl.indexed-test/test-check-permission)
```

### Troubleshooting

#### nREPL Connection Issues:

1. **"Connection refused" errors**: nREPL server is not running
   - Solution: Start nREPL server as described above
   - Verify: Check for `.nrepl-port` file in `/Users/petrus/Code/eacl`

2. **Wrong port**: Using outdated port number
   - Solution: Always use `list_nrepl_ports` to get current port
   - The port changes each time nREPL restarts

3. **Startup fails with "Unknown option: -m"**: Used wrong alias
   - Solution: Use `clojure -M:nrepl` as shown in Quick Start section above
   - The `:test` alias has conflicting main-opts that prevent nREPL from starting

#### MCP Server Issues:

1. Check if server is running: look for `project-0-eacl-clojure-mcp` in MCP server list
2. Restart Claude Code to reconnect
3. Check MCP tool schemas: `ls /Users/petrus/.cursor/projects/Users-petrus-Code-eacl/mcps/project-0-eacl-clojure-mcp/tools/`

### Performance Tips

- **Always use REPL for tests** instead of `clojure -M:test` shell commands
- **Keep nREPL running** between test runs to avoid JVM startup overhead
- **Use `:reload`** when requiring namespaces after code changes
- **Batch evaluations** when possible to reduce round-trips
