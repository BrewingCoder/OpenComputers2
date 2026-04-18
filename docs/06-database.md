# 06 — Database (SQL)

KV alone hits a wall fast for non-trivial logistics. Scripts need real queries — "transfers in last hour", "items below quota", "top 10 pulled items this session". Without a query engine, scripts shoehorn into KV and reinvent SQL badly.

## API style

**SQLite-style parameterized SQL.** Most-familiar shape for scripters across both Lua and JS audiences. No ORM (over-abstracts, doesn't fit dynamic-typed scripting). No query builder (limited, awkward in scripts).

```lua
-- schema (idempotent DDL)
db.exec[[
  CREATE TABLE IF NOT EXISTS quotas (
    item TEXT PRIMARY KEY,
    target INTEGER NOT NULL,
    priority INTEGER DEFAULT 0
  );
  CREATE INDEX IF NOT EXISTS quotas_priority ON quotas(priority);
]]

-- write
db.exec("INSERT OR REPLACE INTO quotas VALUES (?, ?, ?)", "iron_ingot", 200, 5)

-- query
local rows = db.query("SELECT item, target FROM quotas WHERE target > ? ORDER BY priority DESC", 100)
for _, row in ipairs(rows) do
  print(row.item, row.target)
end

-- transactions
db.transaction(function()
  db.exec("UPDATE quotas SET target = target - ? WHERE item = ?", 64, "iron_ingot")
  db.exec("INSERT INTO transfers VALUES (?, ?, ?, ?, ?)", os.time(), src, dst, "iron_ingot", 64)
end)
```

## Tiered availability

| Tier | DB scope | Size cap (default; configurable) |
|---|---|---|
| **Tier 1 Computer** | Local SQLite file on the computer's disk; single-script | 16-64 MB |
| **Tier 2 Control Plane** | Per-script namespace; multi-script (each registered service gets its own DB); shared instance | hundreds of MB |

Big aggregations (transfer history, fleet-wide accounting) live on Control Plane. Local scratch state stays on Tier 1.

## Multi-tenancy

- Each script has its own database/schema namespace
- Scripts cannot see each other's tables
- Cross-script data sharing happens via **explicit channels**: pub/sub, KV store, or RPC
- Same "isolation by default" principle as the rest of the platform

## Async model

DB calls run on a worker thread. Results delivered via coroutine yield (Lua) or promise (JS). Tick-thread DB calls would be lag death.

```lua
-- looks synchronous to the script, actually yields under the hood
local rows = db.query("SELECT ...")
```

## Persistence

- DB persists across world saves (vital — losing logistics history would be catastrophic)
- Survives chunk unloads (DB lives in world data, not chunk data)
- `db.export_json("path")` — explicit backup-to-JSON
- `db.import_json("path")` — restore (admin operation; player can use)

## Resource limits (per-script, configurable per-server)

| Limit | Default | Why |
|---|---|---|
| Max DB size | 64 MB (T1), 512 MB (T2) | Prevent runaway growth |
| Max query duration | 250 ms | Worker thread protection; kill long-runners |
| Max concurrent transactions | 4 | Lock contention bound |
| Max rows per result set | 1000 | Force pagination |
| Max parameters per statement | 64 | Sanity bound |

## Backend choice (open decision)

Lean: **SQLite via sqlite-jdbc**. Reasons:
- Universally known SQL dialect — what every dev expects
- Durable, mature, file-based
- Anyone debugging a stuck script can crack open the DB file in DB Browser for SQLite without OC2 running
- Native lib (per-OS binaries shipped) — one-time setup hassle paid back forever

Fallback: **H2 (pure Java)**. No native deps. Slightly less universal SQL dialect. Worth it if shipping native libs is operationally painful.

NOT picking:
- **DuckDB** — analytical, columnar, wrong fit for OLTP-style logistics writes
- **HSQLDB** — works but lower performance ceiling than H2/SQLite
- **Custom KV + secondary indexes** — NIH death spiral

Lock the choice when implementing.

## What's deliberately NOT in the API

- **No ORM** — over-abstracts, doesn't fit dynamic-typed scripts
- **No raw BLOBs in v1** — encourages external storage; reconsider later if real need emerges
- **No stored procedures** — scripts are the procedures
- **No triggers** — scripts handle reactivity via pub/sub
- **No cross-database queries** — isolation
- **No views in v1** — keep it simple; scripts compose via subqueries

## What scripts CAN'T do

- Open arbitrary file paths as databases
- Execute multi-statement DDL outside the schema namespace
- Bypass the per-script namespace
- Hold locks indefinitely (transaction has hard timeout)

## Stress test: would Isy's-IM-equivalent work?

Yes. Quotas table, current-stock view via JOIN against driver-mediated inventory reads, deficit query, cron job to dispatch transfers. Reactor controller's metric history, alert thresholds, multi-reactor coordination — same shape. The DB earns its place by enabling these without scripts inventing their own indexing.
