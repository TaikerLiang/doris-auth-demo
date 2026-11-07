# doris-auth-demo

Doris SA PoC：平台管理者在 Console 設定各 workspace 的 Doris 權限，再同步到各 fab 的 Doris，
查詢時由 Apache Doris 4.1.4 的原生 RBAC 判斷權限。

目前完成：兩座 Doris（Fab A / Fab B）與 Olist 資料、Spring Boot Console（權限正本 + outbox）。
尚未實作：outbox relay、DLH-Cluster API、fab Redis、DAO，所以 Console 上的權限**還不會**套用到 Doris。

設計文件：[docs/design.md](docs/design.md)、C4 System Context：[docs/c4-context.html](docs/c4-context.html)

## 查詢流程

```text
 DAO / JDBC Client
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
 DAO / JDBC Client
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

預設帳號 `root`，密碼空白。要重新匯入資料：`docker compose run --rm loader`。

Console：http://localhost:8080（Console DB 是 compose 裡的 `console-db`，MariaDB 10.6）。

## Console 功能

Console 只寫 Console DB，不直接連 Doris。每次變更都會在同一個 transaction 裡遞增該 fab 的
`desired_version`，並寫入一筆 `doris_sync_outbox` 事件。

- **Workspaces**：每個 workspace 一列，顯示已開通的 fab 與授權總數。可以搜尋名稱、篩選「有開通某個 fab」的 workspace。
- **Workspace 頁**：列出這個 workspace 已開通的 fab；在下方選 fab 的 Doris host 開通新的 doris path（只列出還沒開通的 fab）。
  開通時會產生 32 字元的隨機 SA 密碼，以 AES-256-GCM 加密存入 `password_enc`。
- **Fab 頁**（workspace × fab）：新增或移除 table 權限（`catalog.database.table` + 權限）、清空 doris path，
  以及把授權複製到其他已開通的 fab。複製只新增目標 fab 缺少的授權，不刪除目標 fab 原有的授權。
- **Doris Hosts**：登記或刪除各 fab 的 Doris instance。
- **同步狀態**：各 fab 的 desired / applied version 與 outbox 事件。

上方導覽列的「跳到 workspace」可以直接輸入名稱切換 workspace。

規則（issue #1 的定案）：

| 情境 | 結果 |
| --- | --- |
| workspace 在該 fab 沒有 doris path 時授權或移除授權 | 403 |
| doris path 還有授權時清空 | 409 |
| 還有 doris path 指向的 Doris host 被刪除 | 409 |
| doris path 指向別的 fab 的 Doris host | 400 |
| catalog / database / table 不是單純識別字（table 可用 `*`） | 400 |

`clusters` 與 `workspaces` 在 ADMC 裡本來就有，PoC 用 Liquibase 建立精簡版，並預先放入
`fab-a`、`fab-b`、三個 workspace，以及 compose 裡的兩座 Doris host。

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

需要 JDK 17 以上與 Maven。測試使用 H2，不需要 MariaDB：

```bash
cd console
mvn test
```

## 清除資料

```bash
docker compose down -v
```
