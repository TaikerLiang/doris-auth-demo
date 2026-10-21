"""Minimal Doris console.

Every request opens a connection with the logged-in user's own Doris credentials,
so all authorization is enforced by Doris' native RBAC, not by this app.
"""

import os
import secrets
import time
from pathlib import Path

import pymysql
from fastapi import Depends, FastAPI, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel

DORIS_HOST = os.getenv("DORIS_HOST", "127.0.0.1")
DORIS_PORT = int(os.getenv("DORIS_PORT", "9030"))
JDBC_URL = f"jdbc:mysql://{DORIS_HOST}:{DORIS_PORT}"
MAX_ROWS = 1000
# MySQL access-denied codes. Doris often wraps RBAC rejections in generic 1105
# ("... command denied to user ..."), so the message is checked too.
DENIED_CODES = {1044, 1045, 1142, 1143, 1227}

BASE = Path(__file__).parent
templates = Jinja2Templates(directory=BASE / "templates")
app = FastAPI(title="Doris Console")
app.mount("/static", StaticFiles(directory=BASE / "static"), name="static")

# session id -> (user, password). In-memory: demo only, cleared on restart.
sessions: dict[str, tuple[str, str]] = {}


def connect(user: str, password: str, database: str | None = None):
    return pymysql.connect(
        host=DORIS_HOST,
        port=DORIS_PORT,
        user=user,
        password=password,
        database=database or None,
        connect_timeout=5,
        autocommit=True,
    )


def cell(v):
    if v is None or isinstance(v, (int, float, str)):
        return v
    if isinstance(v, bytes):
        return v.decode(errors="replace")
    return str(v)


def run(creds: tuple[str, str], sql: str, database: str | None = None) -> dict:
    started = time.perf_counter()
    with connect(*creds, database) as conn, conn.cursor() as cur:
        cur.execute(sql)
        result = {"columns": [], "rows": [], "affected": cur.rowcount, "truncated": False}
        if cur.description:
            rows = cur.fetchmany(MAX_ROWS + 1)
            result.update(
                columns=[d[0] for d in cur.description],
                rows=[[cell(v) for v in r] for r in rows[:MAX_ROWS]],
                truncated=len(rows) > MAX_ROWS,
            )
    result["elapsed_ms"] = round((time.perf_counter() - started) * 1000, 1)
    return result


def as_dicts(result: dict) -> list[dict]:
    return [dict(zip(result["columns"], r)) for r in result["rows"]]


def current_creds(request: Request) -> tuple[str, str]:
    creds = sessions.get(request.cookies.get("sid", ""))
    if not creds:
        raise HTTPException(401, "Not logged in")
    return creds


@app.exception_handler(pymysql.MySQLError)
def mysql_error(_: Request, exc: pymysql.MySQLError):
    code = exc.args[0] if len(exc.args) > 1 else None
    message = exc.args[-1] if exc.args else str(exc)
    denied = code in DENIED_CODES or "denied" in str(message).lower()
    return JSONResponse(
        {"error": {"code": code, "message": message, "denied": denied}},
        status_code=400,
    )


@app.get("/login", response_class=HTMLResponse)
def login_page(request: Request):
    return templates.TemplateResponse(request, "login.html", {"jdbc_url": JDBC_URL})


@app.post("/login")
def login(request: Request, username: str = Form(...), password: str = Form("")):
    try:
        connect(username, password).close()  # the MySQL handshake validates credentials
    except pymysql.MySQLError as exc:
        return templates.TemplateResponse(
            request,
            "login.html",
            {"jdbc_url": JDBC_URL, "username": username, "error": exc.args[-1]},
            status_code=401,
        )
    sid = secrets.token_urlsafe(32)
    sessions[sid] = (username, password)
    response = RedirectResponse("/", status_code=303)
    response.set_cookie("sid", sid, httponly=True, samesite="strict")
    return response


@app.post("/logout")
def logout(request: Request):
    sessions.pop(request.cookies.get("sid", ""), None)
    response = RedirectResponse("/login", status_code=303)
    response.delete_cookie("sid")
    return response


@app.get("/", response_class=HTMLResponse)
def console(request: Request):
    creds = sessions.get(request.cookies.get("sid", ""))
    if not creds:
        return RedirectResponse("/login", status_code=303)
    return templates.TemplateResponse(
        request, "console.html", {"jdbc_url": JDBC_URL, "username": creds[0]}
    )


@app.get("/api/me")
def me(creds=Depends(current_creds)):
    identity = run(creds, "SELECT CURRENT_USER()")["rows"][0][0]
    grants = as_dicts(run(creds, "SHOW GRANTS"))
    return {"current_user": identity, "grants": grants}


@app.get("/api/databases")
def databases(creds=Depends(current_creds)):
    return [r[0] for r in run(creds, "SHOW DATABASES")["rows"]]


@app.get("/api/databases/{db}/tables")
def tables(db: str, creds=Depends(current_creds)):
    name = db.replace("`", "``")
    return [r[0] for r in run(creds, f"SHOW TABLES FROM `{name}`")["rows"]]


class Query(BaseModel):
    sql: str
    database: str | None = None


@app.post("/api/query")
def query(q: Query, creds=Depends(current_creds)):
    return run(creds, q.sql.strip().rstrip(";"), q.database)
