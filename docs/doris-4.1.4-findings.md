# Doris 4.1.4 實測結果

以下結果都是在本 repo 的 docker 環境（`apache/doris:fe-4.1.4` / `be-4.1.4`）實際驗證的，原本收錄在舊版設計文件 `docs/design.md` §7。架構請見 [c4-context-poc.html](./c4-context-poc.html)（[English](./c4-context-poc-en.html)）與 issue #1。

## 權限模型

| 項目 | 結果 |
| --- | --- |
| 角色巢狀 `GRANT 'r1' TO ROLE 'r2'` | ❌ 不支援（語法錯誤） |
| 一個使用者授予多個角色 | ✅ 權限自動合併，不需要 `SET ROLE` |
| table / column / row 三種層級的權限 | ✅ 都能正確生效 |
| 內建角色 `admin`、`operator` | 不能刪除 |
| 權限資料存放位置 | FE metadata（`doris-meta`）。`mysql.user`、`information_schema.*_privileges` 只是唯讀視圖 |

## 重試時 DDL 能否重複執行

| DDL | 結果 |
| --- | --- |
| `CREATE` / `DROP ROLE IF [NOT] EXISTS` | ✅ |
| `CREATE` / `DROP USER IF [NOT] EXISTS` | ✅ |
| `GRANT` / `REVOKE` 角色 | ✅ |
| `GRANT` table 權限、`ALTER USER ... IDENTIFIED BY` | ✅ |
| `REVOKE` table 權限 | ❌ 第二次會報 `... does not exist in role default_role_rbac_<user>`，重試時需要把它視為成功，或先比對現況再 `REVOKE` |
| `CREATE USER IF NOT EXISTS` 帶新密碼 | ⚠️ 不會報錯，但**也不會改密碼**，所以後面必須接 `ALTER USER` |

## 管理帳號權限（只給全域 `GRANT_PRIV` 時）

| 項目 | 結果 |
| --- | --- |
| 管理使用者、角色、row policy | ✅ 可以 |
| `GRANT SELECT_PRIV ON table` | ❌ 授權者自己必須先擁有該權限（`need all [Grant_priv, Select_priv]`） |
| `GRANT 'admin' TO` 自己 | ⚠️ **成功**，可以自行升級成 admin |

結論：全域 `GRANT_PRIV` 實際上等同 admin，所以平台的 Doris admin 帳號直接使用 `ADMIN_PRIV`（不含 `NODE_PRIV`）。

## 待回報 Doris team

- 全域 `GRANT_PRIV` 可以把 `admin` 角色授予給自己。
- 欄位層級的 `GRANT` 不檢查授權者是否擁有該權限，跟 table 層級的行為不一致。
