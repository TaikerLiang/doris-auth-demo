# doris-auth-demo

Demo：透過 FastAPI console 整合 Apache Doris 4.1.4 原生 RBAC。

Console 以登入者自己的 Doris 帳號連線（MySQL 協定，即 JDBC `jdbc:mysql://fe:9030` 使用的協定），
所以所有授權判斷都由 Doris 執行，console 本身不做權限控管。

設計文件：[docs/design.md](docs/design.md)、C4 System Context：[docs/c4-context.html](docs/c4-context.html)

## 查詢流程

```text
 Console / JDBC Client
          │
          │ 1. 以使用者帳密連線（MySQL 協定）
          │ 2. 送出 SQL
          ▼
 ┌─────────────────┐
 │  Doris FE :9030 │  3. 驗證帳密
 │                 │  4. 解析 SQL、檢查權限（RBAC）
 └────────┬────────┘     ※ 使用者 / 角色 / 權限都存在 FE metadata
          │
          ├── 權限不足 ──▶ 直接回傳 ERROR ... denied to user（不會送到 BE）
          │
          │ 權限通過：下發執行計畫
          ▼
 ┌─────────────────┐
 │    Doris BE     │  5. 掃描資料、運算
 └────────┬────────┘
          │ 6. 結果回傳 FE，再由 FE 回給 Client
          ▼
 Console / JDBC Client
```

## 啟動

```bash
docker compose up -d --build --wait
```

| 服務 | 位址 |
| --- | --- |
| Console | http://localhost:8000 |
| Doris FE Web UI | http://localhost:8030 |
| Doris MySQL 協定 | `localhost:9030`（`mysql -uroot -h127.0.0.1 -P9030`） |

預設帳號 `root`，密碼空白。

## Console 功能

- 以 Doris 帳號登入（帳密由 Doris 驗證）
- 側欄顯示 `CURRENT_USER()`、角色，以及 `SHOW GRANTS` 的權限明細
- 資料庫/資料表樹（只會列出該使用者看得到的物件）
- SQL 編輯器；被 RBAC 拒絕的語句會以 **Denied by Doris RBAC** 標示

## 範例：建立受限使用者

以 root 執行：

```sql
CREATE DATABASE sales;
CREATE TABLE sales.orders (id INT, amount DECIMAL(10,2))
  DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1
  PROPERTIES ("replication_num" = "1");
CREATE ROLE sales_reader;
GRANT SELECT_PRIV ON internal.sales.* TO ROLE 'sales_reader';
CREATE USER 'alice'@'%' IDENTIFIED BY 'alice123' DEFAULT ROLE 'sales_reader';
```

以 `alice` 登入後可查詢 `sales.orders`，但 `INSERT` / `CREATE DATABASE` 會被拒絕。

## 本機開發 console

```bash
cd console
uv sync
DORIS_HOST=127.0.0.1 uv run uvicorn app.main:app --reload
```

## 清除資料

```bash
docker compose down -v
```
