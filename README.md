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

## 資料集

使用 Kaggle 的 [Olist Brazilian E-Commerce](https://www.kaggle.com/datasets/olistbr/brazilian-ecommerce)（CC BY-NC-SA 4.0）。
下載需要登入 Kaggle，所以請手動下載 zip，解壓縮到 `data/olist/`（已列入 `.gitignore`）。

loader 會在兩座 Doris 上各建立 `olist` database，並以 Stream Load 匯入其中 6 張表：
`customers`、`orders`、`order_items`、`order_payments`、`products`、`sellers`（略過 `geolocation`、`order_reviews` 和分類翻譯表）。
各表都使用 UNIQUE KEY，所以重跑 loader 不會產生重複資料。

## 啟動

```bash
docker compose up -d --build --wait
docker compose logs -f loader   # 等它印完兩座 Doris 的匯入筆數後結束
```

兩套 Doris cluster 互相獨立，對應 C4 圖中的 Fab A / Fab B，各自有自己的 metadata、帳號與權限。

| 服務 | Fab A | Fab B |
| --- | --- | --- |
| Doris FE Web UI | http://localhost:8030 | http://localhost:8031 |
| Doris MySQL 協定 | `mysql -uroot -h127.0.0.1 -P9030` | `mysql -uroot -h127.0.0.1 -P9031` |
| Doris BE | http://localhost:8040 | http://localhost:8041 |
| Console | http://localhost:8000（連 Fab A） | — |

預設帳號 `root`，密碼空白。要重新匯入資料：`docker compose run --rm loader`。

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
