const $ = (id) => document.getElementById(id);
const HIDDEN_GRANT_FIELDS = new Set(["UserIdentity", "Comment", "Password", "Roles"]);

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  Object.assign(node, attrs);
  node.append(...children);
  return node;
}

async function api(path, options = {}) {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (res.status === 401) {
    location.href = "/login";
    throw new Error("Not logged in");
  }
  const body = await res.json();
  if (!res.ok) throw body.error || { message: JSON.stringify(body) };
  return body;
}

function errorBox(err) {
  const label = err.denied ? "Denied by Doris RBAC" : "Error";
  const code = err.code ? ` · ${err.code}` : "";
  return el("div", { className: `alert ${err.denied ? "denied" : "error"}` },
    el("strong", { textContent: label + code }),
    el("div", { textContent: err.message }));
}

async function loadMe() {
  try {
    const me = await api("/api/me");
    const grant = me.grants[0] || {};
    const roles = (grant.Roles || "").split(",").map((r) => r.trim()).filter(Boolean);
    $("user-chip").textContent = me.current_user;
    $("identity").replaceChildren(
      el("div", { className: "kv" }, el("span", { textContent: "User" }), el("code", { textContent: me.current_user })),
      el("div", { className: "kv" }, el("span", { textContent: "Roles" }),
        el("div", { className: "chips" },
          ...(roles.length ? roles.map((r) => el("span", { className: "chip", textContent: r }))
                           : [el("span", { className: "muted", textContent: "(none)" })]))),
    );
    const privs = Object.entries(grant).filter(([k, v]) => !HIDDEN_GRANT_FIELDS.has(k) && v && v !== "\\N");
    $("privileges").replaceChildren(...(privs.length ? privs.flatMap(([k, v]) => [
      el("dt", { textContent: k }),
      el("dd", {}, ...String(v).split(/;\s*/).filter(Boolean).map((p) => el("div", { textContent: p }))),
    ]) : [el("dd", { className: "muted", textContent: "(no privileges)" })]));
  } catch (err) {
    $("identity").replaceChildren(errorBox(err));
  }
}

async function loadDatabases() {
  const select = $("database");
  const current = select.value;
  try {
    const dbs = await api("/api/databases");
    select.replaceChildren(el("option", { value: "", textContent: "(none)" }),
      ...dbs.map((db) => el("option", { value: db, textContent: db, selected: db === current })));
    $("db-tree").replaceChildren(...dbs.map(dbNode));
  } catch (err) {
    $("db-tree").replaceChildren(errorBox(err));
  }
}

function dbNode(db) {
  const tables = el("ul", { hidden: true });
  const label = el("button", { className: "node db", textContent: db });
  label.onclick = async () => {
    $("database").value = db;
    tables.hidden = !tables.hidden;
    if (tables.hidden) return;
    tables.replaceChildren(el("li", { className: "muted", textContent: "Loading…" }));
    try {
      const names = await api(`/api/databases/${encodeURIComponent(db)}/tables`);
      tables.replaceChildren(...(names.length ? names.map((t) => {
        const btn = el("button", { className: "node table", textContent: t });
        btn.onclick = () => {
          $("sql").value = `SELECT * FROM \`${db}\`.\`${t}\` LIMIT 100`;
          runQuery();
        };
        return el("li", {}, btn);
      }) : [el("li", { className: "muted", textContent: "(no tables)" })]));
    } catch (err) {
      tables.replaceChildren(el("li", {}, errorBox(err)));
    }
  };
  return el("li", {}, label, tables);
}

function renderTable(result) {
  if (!result.columns.length) {
    return el("div", { className: "alert ok", textContent: `OK · ${result.affected} row(s) affected` });
  }
  return el("div", { className: "table-wrap" },
    el("table", {},
      el("thead", {}, el("tr", {}, ...result.columns.map((c) => el("th", { textContent: c })))),
      el("tbody", {}, ...result.rows.map((row) => el("tr", {},
        ...row.map((v) => el("td", { textContent: v === null ? "NULL" : v, className: v === null ? "null" : "" })))))));
}

function addHistory(sql, outcome) {
  const item = el("li", {},
    el("span", { className: `badge ${outcome}`, textContent: outcome }),
    el("code", { textContent: sql }));
  item.onclick = () => { $("sql").value = sql; $("sql").focus(); };
  $("history").prepend(item);
}

async function runQuery() {
  const sql = $("sql").value.trim();
  if (!sql) return;
  $("run").disabled = true;
  $("status").textContent = "Running…";
  try {
    const result = await api("/api/query", {
      method: "POST",
      body: JSON.stringify({ sql, database: $("database").value || null }),
    });
    const rows = result.columns.length ? `${result.rows.length}${result.truncated ? "+ (truncated)" : ""} row(s)` : "done";
    $("status").textContent = `${rows} · ${result.elapsed_ms} ms`;
    $("result").replaceChildren(renderTable(result));
    addHistory(sql, "ok");
    if (/^\s*(create|drop|grant|revoke|alter|set\s+role)/i.test(sql)) {
      loadMe();
      loadDatabases();
    }
  } catch (err) {
    $("status").textContent = "";
    $("result").replaceChildren(errorBox(err));
    addHistory(sql, err.denied ? "denied" : "error");
  } finally {
    $("run").disabled = false;
  }
}

$("run").onclick = runQuery;
$("refresh-dbs").onclick = loadDatabases;
$("sql").addEventListener("keydown", (e) => {
  if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
    e.preventDefault();
    runQuery();
  }
});

loadMe();
loadDatabases();
