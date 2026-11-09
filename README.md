# doris-auth-demo

Doris SA PoC：平台管理者在 Console 設定各 workspace 的 Doris 權限，再同步到各 fab 的 Doris，
查詢時由 Apache Doris 4.1.4 的原生 RBAC 判斷權限。

目前完成：兩座 Doris（Fab A / Fab B）與 Olist 資料、Spring Boot Console（權限正本 + outbox + relay）、
各 fab 的 DLH-Cluster API（比對並套用到 Doris、寫 fab Redis）。在 Console 改權限，約 10 秒內就會生效到 Doris。
權限生命週期有 e2e 驗證（`docker compose run --rm e2e`）。尚未實作：DAO（從 fab Redis 取憑證連 Doris 的 client）。

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
| Cluster API | http://localhost:8091 | http://localhost:8092 |
| fab Redis | `docker compose exec redis-a redis-cli` | `docker compose exec redis-b redis-cli` |

預設帳號 `root`，密碼空白。要重新匯入資料：`docker compose run --rm loader`。

Console：http://localhost:8081（Console DB 是 compose 裡的 `console-db`，MariaDB 10.6）。

## Console 功能

Console 只寫 Console DB，不直接連 Doris。每次變更都會在同一個 transaction 裡遞增該 fab 的
`desired_version`，並寫入一筆 `doris_sync_outbox` 事件；排程再把事件送到該 fab 的 Cluster API（同步段 1）：

| Job | 頻率 | 作用 |
| --- | --- | --- |
| `outbox-relay` | 每 5 秒 | 有待送事件的 fab，送出它目前的完整狀態（只送最新版本）。失敗時從 5 秒開始指數退避，最多 5 分鐘 |
| `periodic-sync` | 每 30 分 | 每個 fab 補一筆事件，讓漏送或 Cluster DB 被清空的 fab 追回完整狀態 |
| `status-poll` | 每 30 秒 | 讀回各 fab 的 applied version、錯誤與漂移數，顯示在同步狀態頁 |

各 fab 的 Cluster API endpoint 設在 `console.clusters.endpoints`（compose 用 `SPRING_APPLICATION_JSON` 設定）。
Console 的排程鎖放在自己的 `console-redis`，那裡不放任何 SA 憑證。

- **Workspaces**：每個 workspace 一列，顯示已開通的 fab 與授權總數。可以搜尋名稱、篩選「有開通某個 fab」的 workspace。
- **Workspace 頁**：列出這個 workspace 已開通的 fab（SA 數、授權數）；在下方選 fab 的 Doris host 開通新的 doris path
  （只列出還沒開通的 fab）。doris path 只是白名單，代表這個 workspace 可以在該 fab 的 Doris 上建 SA。
- **Fab 頁**（workspace × fab）：一個 workspace 在一個 fab 可以有多個 SA。
  - 新增 SA：填 username（預設 `sa_<workspace>_`）與用途說明，系統產生 32 字元英數密碼，以 AES-256-GCM 加密存入
    `password_enc`。**密碼只在建立或「重新產生密碼」當下顯示一次**（可一鍵複製），之後只顯示「已加密」。
  - 重新產生密碼：同步後舊密碼立即失效，fab Redis 的憑證與 `cred_version` 跟著更新，DAO 不需要手動設定。
  - 每個 SA 各自的 table 權限（`catalog.database.table` + 權限），以及把授權複製到同一個 workspace 的其他 SA
    （可跨 fab）。複製只新增目標缺少的授權，不刪除目標原有的授權。
- **Doris Hosts**：登記或刪除各 fab 的 Doris instance。
- **同步狀態**：各 fab 的 desired / applied version（不一致就標「落後」）、待送事件與重試、最後的錯誤、
  漂移數，以及「立即同步」按鈕（也可以 `POST /api/sync/{fab}`）。applied 才代表已經套用到 Doris。

上方導覽列的「跳到 workspace」可以直接輸入名稱切換 workspace。

規則（issue #1 的定案）：

| 情境 | 結果 |
| --- | --- |
| workspace 在該 fab 沒有 doris path 時新增 SA、授權或移除授權 | 403 |
| doris path 還有 SA 時清空 | 409 |
| SA 還有授權時刪除 | 409 |
| username 在同一座 Doris 上已存在 | 409 |
| username 不是英數與 `_`，或是 `root`、`admin` | 400 |
| 還有 doris path 指向的 Doris host 被刪除 | 409 |
| doris path 指向別的 fab 的 Doris host | 400 |
| catalog / database / table 不是單純識別字（table 可用 `*`） | 400 |

`clusters` 與 `workspaces` 在 ADMC 裡本來就有，PoC 用 Liquibase 建立精簡版，並預先放入
`fab-a`、`fab-b`、三個 workspace，以及 compose 裡的兩座 Doris host。

## Cluster API（同步段 2）

Console 的 relay 會自動呼叫；下面的 curl 只在單獨測試 Cluster API 時用得到。每個 fab 一套：`cluster-api-a`（http://localhost:8091）管 fe-a 與 `redis-a`，`cluster-api-b`（http://localhost:8092）
管 fe-b 與 `redis-b`。Cluster DB 是 `cluster-db`，每個 fab 一個 schema。

| API | 作用 |
| --- | --- |
| `PUT /doris/state` | 收下該 fab 的完整期望狀態（hosts、paths 含明文密碼、grants、version）；不比 `received_version` 新的版本直接忽略 |
| `POST /doris/trigger` | 排入一次套用（回 202），由 `doris-apply` 在幾秒內執行；也用來修正漂移 |
| `GET /doris/status` | `receivedVersion`、`appliedVersion`、`lastError`、`lastDrift` |

> **連線 Doris 的實作不在此 repo**（#24）：`Reconciler` 透過 `DorisClient` 介面讀權限、執行 SQL，預設的 `UnconfiguredDorisClient` 一律拒絕連線，所以每次套用都會在 `lastError` 回報「沒有 DorisClient 實作」，Redis 不會被改動。要實際套用，請提供一個標 `@Primary` 的 `DorisClient` bean（用平台 admin 帳號連 FE、以 `GrantParser` 解析 `SHOW ALL GRANTS`、逐筆執行 `ReconcilePlanner` 產生的語句）。

套用時用平台 admin 帳號讀 `SHOW ALL GRANTS`，只補差異：缺的 SA `CREATE USER`、多的 `DROP USER`、
缺的權限 `GRANT`、多的 `REVOKE`。只動 Cluster 自己建立過的 SA（記在 `doris_managed_users`），
人工建立的帳號一律不碰；`information_schema`、`mysql` 這類內建 database 的權限也不碰。
一筆失敗（例如 table 不存在）不影響其他 SA；全部成功才更新 `appliedVersion`。
憑證寫進 fab Redis：先寫 `doris:<instance>:cred:<sa_id>`，再遞增 `doris:<instance>:cred_version`。

排程（設定在 `cluster-api/src/main/resources/application.yaml` 的 `scheduler.jobs`）：

| Job | 頻率 | 作用 |
| --- | --- | --- |
| `doris-apply` | 每 5 秒 | 執行待處理的套用事件（新的 snapshot、trigger、失敗重試） |
| `doris-reconcile` | 每 10 分 | 版本沒變也重新套用一次，修正 Doris 上被手動改掉的權限 |

兩個 job 共用 Redis 鎖 `scheduler:<fab>:doris`，同一個 fab 不論有幾個 replica，同時只有一個在動 Doris；
拿不到鎖的那一輪會跳過並記 log。鎖由 Redisson watchdog 續約，持有者掛掉後約 30 秒自動釋放。
做法參考 [Spring Scheduler Solution](https://blog.taiker.space/spring-scheduler-solution/)（#9）。

```bash
curl -X PUT localhost:8091/doris/state -H 'Content-Type: application/json' -d '{
  "cluster": "fab-a", "version": 1,
  "hosts":  [{"id": "h-a", "instanceName": "doris-fab-a", "feHost": "fe-a", "queryPort": 9030, "httpPort": 8030, "secretRef": "DORIS_FAB_A_ADMIN"}],
  "paths":  [{"saId": "s1", "workspace": "workspace-a", "hostId": "h-a", "username": "sa_workspace_a", "password": "Pw1aaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],
  "grants": [{"saId": "s1", "catalog": "internal", "database": "olist", "table": "orders", "privilege": "SELECT_PRIV"}]
}'
sleep 5   # doris-apply 每 5 秒執行一次
mysql -usa_workspace_a -pPw1aaaaaaaaaaaaaaaaaaaaaaaaaaaaa -h127.0.0.1 -P9030 -e 'select count(*) from olist.orders'
docker compose exec redis-a redis-cli GET doris:doris-fab-a:cred:s1
```

用 curl 測過之後，Cluster DB 的 `received_version` 可能比 Console 的版本還新；要接 Console 前先重設：
`docker compose rm -sf cluster-api-a cluster-api-b cluster-db && docker volume rm doris-auth-demo_cluster-db`。

## e2e 驗證（Desired State 與 Actual State 一致性）

```bash
docker compose up -d --build --wait   # 整套服務都要在跑
docker compose run --rm e2e           # 約 3 分鐘；全部通過時結束碼為 0
```

> 需要先提供 `DorisClient` 實作（見上方 Cluster API 一節），否則套用不會生效、e2e 會失敗。

腳本（`e2e/run.sh`）透過 Console 的 HTTP 端點改權限，再直接查 Doris 的 `SHOW ALL GRANTS`，
和 Console DB 的期望狀態逐筆比對（每個 SA 與每筆權限都要一致，多一個沒人管的 `sa_*` 帳號也算不一致）。
它用自己的 workspace `e2e-a`、`e2e-b`，跑完會把加過的東西都移除；故障情境透過 Docker socket 停掉再啟動服務。

| # | 情境 | 驗證什麼 |
| --- | --- | --- |
| 1 | 正常生效 | 開通並授權後 Doris 一致；用 fab Redis 的帳密登入，有權限的 table 可查、其他被拒 |
| 2 | 撤銷 | 先 `REVOKE`、刪除 SA 後 `DROP USER`；舊密碼不能建立新連線；Redis 的 cred key 被刪除 |
| 3 | 部分 fab 同步失敗 | 停掉 cluster-api-b：fab-a 照常生效，fab-b 落後並退避重試，恢復後追到最新版本 |
| 4 | Doris 掛掉 | 停掉 fe-b：Cluster 回報錯誤，FE 恢復後自動補上 |
| 5 | 錯誤的授權 | 不存在的 table：同 fab 其他 SA 照常生效，`lastError` 說明原因，applied 不前進 |
| 6 | 權限漂移 | 用 root 手動 `GRANT`／`REVOKE`／`DROP USER` 後觸發同步：修回一致，回報漂移數 |
| 7 | 不碰人工帳號 | 人工建立的 `sa_manual` 跑完所有情境後不變 |
| 8 | 冪等 | 重複觸發同一個版本：不執行任何 SQL（漂移數 0） |
| 9 | 複製授權 | fab-a 的授權複製到 fab-b，fab-b 原本多的授權保留 |
| 10 | 撤銷對既有連線的影響 | Doris 4.1.4 實測：`REVOKE` 對既有連線立即生效；`DROP USER` **不會斷線**，但該連線查任何 table 都被拒 |
| 11 | 重新產生密碼 | 同步後 Redis 的密碼與 `cred_version` 更新；新密碼可登入、舊密碼被拒 |

情境 6 用「立即同步」（`POST /api/sync/fab-a`）觸發，不等每 10 分鐘的 `doris-reconcile`；兩者走同一段 reconcile。
每一步最多等 `E2E_WAIT` 秒（預設 180），涵蓋 relay 的退避與 FE 重啟。

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

## 本機開發

需要 JDK 17 以上與 Maven。測試使用 H2，不需要 MariaDB、Doris 或 Redis：

```bash
cd console && mvn test
cd cluster-api && mvn test
```

## 清除資料

```bash
docker compose down -v
```
