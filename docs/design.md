# Doris 權限整合設計（共識版）

> 狀態：Auth / Doris / Client（DAO）三個團隊討論後的共識，2026-10-22
> C4 System Context 圖：[`c4-context.html`](./c4-context.html)

本文件只收錄已達成共識的決策。尚未確認的項目集中在[第 8 節](#8-待確認事項)。

## 1. 目標

- 權限最終綁定到「人」，但為了方便，也可以綁定在 department 或 section。
- 資料權限由 Data Owner 決定，Auth / Doris / Client 三個團隊都不決定誰能看什麼。
- 權限判斷交給 Doris 原生 RBAC，DAO 和 Tool Console 都不在查詢路徑上過濾資料。

組織架構：`department → section → person`，約 8 萬人。

## 2. 決策

| # | 決策 |
| --- | --- |
| D1 | Doris 只做**內建帳號密碼驗證 + RBAC**，套用帳號上已設定好的權限。不使用 OIDC authentication integration，也不使用 Doris 的 `ROLE MAPPING`。 |
| D2 | **每個人一個 Doris 帳號**：帳號名稱 = DAO 的 user_id，密碼由 Tool Console 隨機產生。 |
| D3 | 組織與權限的對應由 Tool Console 維護，再同步到 Doris（物件對應見第 3 節）。 |
| D4 | DAO 從**驗證過簽章的 token** 取得 user_id 和 section id，不信任 client 自帶的參數。 |
| D5 | DAO 從共用的 Redis 讀取密碼，**以使用者本人的身分**連線 Doris。DAO 不做權限判斷。 |
| D6 | 密碼**正本加密存放在 Tool Console DB**，Redis 只是發給 DAO 使用的副本。 |
| D7 | Tool Console 用 **outbox pattern** 同步到 Doris 和 Redis，relay 由 **Tool Console 內部輪詢**執行，不使用 message broker。 |
| D8 | Data Owner 透過 Tool Console **既有的 approval workflow** 決定權限，擁有範圍由 Tool Console 檢查。 |
| D9 | Tool Console 使用 Doris team 提供的 **`ADMIN_PRIV` 帳號（不含 `NODE_PRIV`）**，帳密存放在 secret manager。 |

## 3. 組織對應到 Doris 物件

| 組織 / 權限 | Doris 物件 | 範例 |
| --- | --- | --- |
| department | 角色 | `CREATE ROLE dept_finance` |
| section | 角色 | `CREATE ROLE sec_ap01` |
| person | 使用者，並授予所屬的 department 和 section 角色 | `CREATE USER 'u12345'@'%'` + `GRANT 'dept_finance', 'sec_ap01' TO 'u12345'@'%'` |
| table 權限（綁 section） | 授權給角色 | `GRANT SELECT_PRIV ON internal.sales.orders TO ROLE 'sec_ap01'` |
| column 權限 | 欄位層級授權 | `GRANT SELECT_PRIV(id, name) ON internal.hr.emp TO ROLE 'sec_ap01'` |
| row 權限 | Row policy | `CREATE ROW POLICY rp_ap01 ON hr.emp AS RESTRICTIVE TO ROLE sec_ap01 USING (section = 'AP01')` |
| 個人例外權限 | 直接授權給使用者 | `GRANT SELECT_PRIV ON internal.finance.budget TO 'u12345'@'%'` |

Doris 的角色**不能巢狀**，角色只能授予給使用者。所以 department → section 的層級要在授權時攤平：每個人都要同時被授予 department 角色和 section 角色。

使用者的實際權限 = 所有角色的權限 + 直接授予給他的權限（聯集）。

## 4. 流程

### 4.1 權限申請到生效

```text
 Requester
     │ (1) 提出申請：section 或個人 → table / column / row
     ▼
 ┌──────────────────────────────┐
 │         Tool Console         │ ◀── (2) Data Owner 在 approval workflow 核准
 └──────────────┬───────────────┘
                │ (3) 同一個 transaction 寫入：權限正本 + outbox 事件
                ▼
 ┌──────────────────────────────┐
 │    Relay (in Tool Console)   │ ◀── 輪詢 outbox，失敗重試
 └──────────────┬───────────────┘
                │ (4) GRANT / REVOKE / CREATE ROW POLICY
                ▼
 ┌──────────────────────────────┐
 │        Doris Platform        │
 └──────────────────────────────┘
```

### 4.2 建立人員帳號

```text
 ┌──────────────────────────────┐
 │         Tool Console         │ ◀── 組織資料新增 person
 └──────────────┬───────────────┘
                │ (1) 產生隨機密碼，加密存入正本，並寫入 outbox 事件
                ▼
 ┌──────────────────────────────┐
 │    Relay (in Tool Console)   │
 └───────┬──────────────┬───────┘
         │              │
         │ (2)          │ (3)
         ▼              ▼
 ┌──────────────┐ ┌──────────────┐
 │    Doris     │ │    Redis     │
 └──────────────┘ └──────────────┘

 (2) CREATE USER IF NOT EXISTS → ALTER USER 設定密碼 → GRANT dept / section 角色
 (3) SET user_id → password
```

### 4.3 查詢

```text
 Data User
     │ (1) request + JWT
     ▼
 ┌──────────────────────────────┐
 │         DAO Service          │ ── (2) 驗證 JWT 簽章，取出 user_id
 └──────┬────────────────┬──────┘
        │                │
        │ (4)            │ (3)
        ▼                ▼
 ┌──────────────┐ ┌──────────────┐
 │    Doris     │ │    Redis     │
 └──────────────┘ └──────────────┘

 (3) GET user_id → password
 (4) 以 user_id / password 連線（MySQL protocol / JDBC），Doris 驗證帳密後套用該帳號的權限
```

## 5. 跨團隊假設

Auth 的設計會產生「每個使用者一個 Doris 帳號」。DAO 必須以使用者本人的身分連線，連線數與同時活躍的使用者數成正比。

- **Doris team**：評估 `qe_max_connection`（預設每台 FE 1024 條）和 FE 的數量。
- **Client team**：設計依使用者分開的連線池和閒置逾時。

另外，每個帳號預設 `max_user_connections = 100`。

## 6. 範圍外與已知風險

**範圍外**

- 人員離職、調動的偵測與處理：Tool Console 目前無法得知。
- 效能與容量規劃：由 Doris team 負責。

**已知風險**

- 離職者的 Doris 帳號、Redis 裡的密碼和權限都會殘留。目前只靠「IdP 停用後，DAO 驗不過 token」來擋。
- DAO 能以任何人的身分連線，必須被完全信任。Doris RBAC 能防止 DAO 的查詢邏輯出錯，但無法防止 DAO 本身被入侵。
- Tool Console 的 `ADMIN_PRIV` 帳號可以讀寫所有資料。

## 7. Doris 4.1.4 實測結果

以下結果都是在本 repo 的 docker 環境（`apache/doris:fe-4.1.4` / `be-4.1.4`）實際驗證的。

**權限模型**

| 項目 | 結果 |
| --- | --- |
| 角色巢狀 `GRANT 'r1' TO ROLE 'r2'` | ❌ 不支援（語法錯誤） |
| 一個使用者授予多個角色 | ✅ 權限自動合併，不需要 `SET ROLE` |
| table / column / row 三種層級的權限 | ✅ 都能正確生效 |
| 內建角色 `admin`、`operator` | 不能刪除 |
| 權限資料存放位置 | FE metadata（`doris-meta`）。`mysql.user`、`information_schema.*_privileges` 只是唯讀視圖 |

**Relay 重試時 DDL 能否重複執行**

| DDL | 結果 |
| --- | --- |
| `CREATE` / `DROP ROLE IF [NOT] EXISTS` | ✅ |
| `CREATE` / `DROP USER IF [NOT] EXISTS` | ✅ |
| `GRANT` / `REVOKE` 角色 | ✅ |
| `GRANT` table 權限、`ALTER USER ... IDENTIFIED BY` | ✅ |
| `REVOKE` table 權限 | ❌ 第二次會報 `... does not exist in role default_role_rbac_<user>`，relay 需要把它視為成功 |
| `CREATE USER IF NOT EXISTS` 帶新密碼 | ⚠️ 不會報錯，但**也不會改密碼**，所以後面必須接 `ALTER USER` |

**Tool Console 帳號權限（只給全域 `GRANT_PRIV` 時）**

| 項目 | 結果 |
| --- | --- |
| 管理使用者、角色、row policy | ✅ 可以 |
| `GRANT SELECT_PRIV ON table` | ❌ 授權者自己必須先擁有該權限（`need all [Grant_priv, Select_priv]`） |
| `GRANT 'admin' TO` 自己 | ⚠️ **成功**，可以自行升級成 admin |

結論：全域 `GRANT_PRIV` 實際上等同 admin，所以直接使用 `ADMIN_PRIV` 帳號（D9）。

## 8. 待確認事項

這些是討論中提出、但還沒有明確共識的建議：

- **Relay 的順序規則**
  - 同一個 user_id 的事件要依序處理。
  - 一律先改 Doris、再寫 Redis。
  - DAO 遇到 `1045` 時，重新讀一次 Redis 再重試。
- **outbox 事件內容**：只帶 user_id，不存明文密碼。relay 執行時再從正本讀出並解密。
- **Redis 設定**
  - `maxmemory-policy noeviction`，並開啟 AOF 持久化。
  - 用 ACL 限制 DAO 只能讀、Tool Console 才能寫，並開啟 TLS。
  - 要有從正本重建 Redis 的機制。
- **資料擁有權的粒度**：以 database 還是以 table 為單位？由誰指派 owner？
- **回報 Doris team**
  - 全域 `GRANT_PRIV` 可以把 `admin` 角色授予給自己。
  - 欄位層級的 `GRANT` 不檢查授權者是否擁有該權限，跟 table 層級的行為不一致。
- **Tool Console 帳號的監控**：從 `__internal_schema.audit_log` 監控異常操作。
