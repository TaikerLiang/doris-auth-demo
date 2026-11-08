#!/usr/bin/env bash
# Usage: docker compose run --rm e2e
# Changes permissions through the Console and checks that each fab's Doris ends up with exactly the
# Console's desired state (#12). Uses its own workspaces e2e-a / e2e-b and removes everything it adds.
set -uo pipefail

CONSOLE=http://console:8080
declare -A FE=([fab-a]=fe-a [fab-b]=fe-b)
declare -A API=([fab-a]=http://cluster-api-a:8080 [fab-b]=http://cluster-api-b:8080)
declare -A REDIS=([fab-a]=redis-a [fab-b]=redis-b)
declare -A INSTANCE=([fab-a]=doris-fab-a [fab-b]=doris-fab-b)
declare -A HOST_ID=([fab-a]=7d1f3a52-0a5e-4c1a-9a43-6f2b1c0e0a01 [fab-b]=7d1f3a52-0a5e-4c1a-9a43-6f2b1c0e0b01)
WAIT=${E2E_WAIT:-180} # seconds; covers relay backoff and an FE restart
PROJECT=$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$HOSTNAME")
PASSED=0
FAILED=()

# ---------- helpers ----------

scenario() { echo; echo "=== $*"; }
pass() { echo "  ✓ $*"; PASSED=$((PASSED + 1)); }
fail() { echo "  ✗ $*"; FAILED+=("$CURRENT: $*"); }
check() { # check "description" command...: the command runs in this shell, so it can be any function
  local what=$1; shift
  if "$@"; then pass "$what"; else fail "$what"; fi
}
not() { ! "$@"; }
output_is() { local want=$1; shift; [[ $("$@") == "$want" ]]; }
output_has() { local want=$1; shift; [[ $("$@") == *"$want"* ]]; }

sql() { mariadb --skip-ssl -N -B "$@"; } # neither Doris nor the PoC MariaDB has TLS
console_sql() { sql -h console-db -uconsole -pconsole console -e "$1"; }
doris() { local fab=$1; shift; sql -h "${FE[$fab]}" -P9030 -uroot "$@"; }
as_sa() { sql -h "${FE[$1]}" -P9030 -u"$2" -p"$3" -e "$4" 2>&1; }
post() { curl -s -o /dev/null -w '%{http_code}' -X POST "$CONSOLE$1" "${@:2}"; }
service() { # service stop|start <compose service>
  docker "$1" "$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT" \
    --filter "label=com.docker.compose.service=$2")" >/dev/null
}

# Each e2e workspace has one SA per fab, named sa_<workspace>
sa_id() {
  console_sql "select s.id from doris_sas s join workspace_doris w on w.id = s.workspace_doris_id
    where w.workspace_name='$1' and w.cluster_name='$2'"
}
grant_id() { # grant_id <workspace> <fab> <table> <privilege>
  console_sql "select g.id from doris_sa_grants g join doris_sas s on s.id = g.sa_id
    join workspace_doris w on w.id = s.workspace_doris_id
    where w.workspace_name='$1' and w.cluster_name='$2' and g.table_name='$3' and g.privilege='$4'"
}
password() { redis-cli -h "${REDIS[$2]}" GET "doris:${INSTANCE[$2]}:cred:$(sa_id "$1" "$2")" | jq -r .password; }
status() { curl -s "${API[$1]}/doris/status" | jq -r ".$2 // empty"; }
retrying() { (( $(console_sql "select coalesce(max(retry_count), 0) from doris_sync_outbox where cluster_name='$1' and status='PENDING'") > 0 )); }
has_grants() { # has_grants <workspace> <fab> <table>...: SELECT_PRIV on each, per the Console
  local ws=$1 fab=$2 table; shift 2
  for table in "$@"; do [[ -n $(grant_id "$ws" "$fab" "$table" SELECT_PRIV) ]] || return 1; done
}
doris_grants() { doris "$1" -e "SHOW GRANTS FOR '$2'@'%'" 2>/dev/null | cut -f8,9; }

open_path() { # open_path <workspace> <fab>: the doris path plus its SA sa_<workspace>
  post "/workspaces/$1/paths" -d "hostId=${HOST_ID[$2]}" >/dev/null
  post "/workspaces/$1/fabs/$2/sas" -d "username=sa_${1//-/_}&description=e2e" >/dev/null
}
add_grant() { # add_grant <workspace> <fab> <table> [privilege]
  post "/workspaces/$1/fabs/$2/sas/$(sa_id "$1" "$2")/grants" -d "catalog=internal&database=olist&table=$3&privilege=${4:-SELECT_PRIV}" >/dev/null
}
remove_grant() { post "/workspaces/$1/fabs/$2/grants/$(grant_id "$1" "$2" "$3" "${4:-SELECT_PRIV}")/delete" >/dev/null; }
clear_path() { # clear_path <workspace> <fab>: deletes its SA, then the doris path
  [[ -n $(sa_id "$1" "$2") ]] && post "/workspaces/$1/fabs/$2/sas/$(sa_id "$1" "$2")/delete" >/dev/null
  post "/workspaces/$1/fabs/$2/path/delete" >/dev/null
}

# What the Console says should be on the fab's Doris: one "user - -" line per SA plus one line per grant.
desired() {
  console_sql "select s.username, '-', '-' from doris_sas s join workspace_doris w on w.id = s.workspace_doris_id
    where w.cluster_name='$1'
    union all
    select s.username, concat(g.catalog_name, '.', g.database_name, '.', g.table_name), g.privilege
    from doris_sa_grants g join doris_sas s on s.id = g.sa_id join workspace_doris w on w.id = s.workspace_doris_id
    where w.cluster_name='$1'" \
    | tr '\t' ' ' | sort
}

# What the fab's Doris has, in the same form, for every sa_* user except the hand-made sa_manual.
actual() {
  doris "$1" -e "SHOW ALL GRANTS" | gawk -F'\t' '
    function emit(column, parts,   n, entries, i, sep, target, privs, t, p, j) {
      if (column == "NULL" || column == "") return
      n = split(column, entries, "; ")
      for (i = 1; i <= n; i++) {
        sep = index(entries[i], ": ")
        target = substr(entries[i], 1, sep - 1); privs = substr(entries[i], sep + 2)
        split(target, t, ".")
        if (t[2] == "information_schema" || t[2] == "mysql" || t[2] == "__internal_schema") continue
        if (parts == 2) target = target ".*"
        split(privs, p, ",")
        for (j in p) print name, target, toupper(p[j])
      }
    }
    match($1, /^'"'"'([^'"'"']*)'"'"'@'"'"'%'"'"'$/, m) {
      name = m[1]
      if (name !~ /^sa_/ || name == "sa_manual") next
      print name, "-", "-"
      emit($8, 2); emit($9, 3)
    }' | sort
}

consistent() { diff <(desired "$1") <(actual "$1") >/dev/null; }
show_diff() { diff <(desired "$1") <(actual "$1") | sed 's/^/      /'; }

# Doris has applied the Console's current version and nothing is waiting in the outbox.
synced() {
  local desired_version pending
  desired_version=$(console_sql "select desired_version from doris_cluster_sync where cluster_name='$1'")
  pending=$(console_sql "select count(*) from doris_sync_outbox where cluster_name='$1' and status='PENDING'")
  [[ $(status "$1" appliedVersion) == "$desired_version" && $pending == 0 ]]
}

wait_for() { # wait_for "description" command...: polls until true or $WAIT seconds
  local what=$1 start=$SECONDS; shift
  until "$@"; do
    if (( SECONDS - start >= WAIT )); then fail "$what（等了 ${WAIT}s）"; return 1; fi
    sleep 2
  done
  pass "$what（$((SECONDS - start))s）"
}
assert_consistent() {
  if consistent "$1"; then pass "$1：Doris 與 Console 一致"; else fail "$1：Doris 與 Console 不一致"; show_diff "$1"; fi
}

# A mysql session that stays open, to see what REVOKE / DROP USER do to existing connections.
open_session() {
  coproc SESSION { sql -h "${FE[$1]}" -P9030 -u"$2" -p"$3" -n --force 2>&1; }
}
in_session() {
  local line out=""
  echo "$1; select '__END__';" >&"${SESSION[1]}"
  while IFS= read -r -t 20 line <&"${SESSION[0]}"; do
    [[ $line == __END__ ]] && break
    out+="$line"$'\n'
  done
  printf '%s' "$out"
}

cleanup() {
  service start cluster-api-b
  service start fe-b
  for ws in e2e-a e2e-b; do
    for fab in fab-a fab-b; do
      console_sql "select g.table_name, g.privilege from doris_sa_grants g join doris_sas s on s.id = g.sa_id
        join workspace_doris w on w.id = s.workspace_doris_id where w.workspace_name='$ws' and w.cluster_name='$fab'" \
        | while read -r table privilege; do remove_grant "$ws" "$fab" "$table" "$privilege"; done
      [[ -n $(console_sql "select id from workspace_doris where workspace_name='$ws' and cluster_name='$fab'") ]] \
        && clear_path "$ws" "$fab"
    done
  done
  doris fab-a -e "DROP USER IF EXISTS 'sa_manual'@'%'" 2>/dev/null
}

# ---------- setup ----------

echo "e2e against compose project $PROJECT (wait up to ${WAIT}s per step)"
CURRENT=setup; scenario "準備：清掉上次中斷留下的資料，兩個 fab 先同步一次"
cleanup
# A fresh deployment has no outbox event yet (Console at version 0, Cluster applied null), so ask for one
for fab in fab-a fab-b; do
  post "/api/sync/$fab" >/dev/null
  wait_for "$fab 初始同步" synced "$fab" || { echo "初始同步失敗，停止"; exit 1; }
done
console_sql "delete from workspaces where workspace_name in ('e2e-a', 'e2e-b');
  insert into workspaces values ('e2e-a'), ('e2e-b')"
# Scenario 7 needs an account a human made, with the prefix Console uses
doris fab-a -e "CREATE USER 'sa_manual'@'%' IDENTIFIED BY 'Manual1234567890'; GRANT SELECT_PRIV ON internal.olist.sellers TO 'sa_manual'@'%'"
MANUAL_BEFORE=$(doris_grants fab-a sa_manual)
trap 'cleanup' EXIT

# ---------- scenarios ----------

CURRENT=1; scenario "1. 正常生效：開通 doris path，授權 olist.orders"
open_path e2e-a fab-a
add_grant e2e-a fab-a orders
wait_for "fab-a 套用到最新版本" synced fab-a
assert_consistent fab-a
PW=$(password e2e-a fab-a)
check "用 Redis 帳密登入，SELECT orders 成功" output_is 99441 as_sa fab-a sa_e2e_a "$PW" "select count(*) from olist.orders"
check "SELECT customers 被拒" output_has "Access denied" as_sa fab-a sa_e2e_a "$PW" "select count(*) from olist.customers"

CURRENT=3; scenario "3. 部分 fab 同步失敗：停掉 cluster-api-b，兩個 fab 都改"
service stop cluster-api-b
open_path e2e-a fab-b
add_grant e2e-a fab-b '*'
add_grant e2e-a fab-a customers
wait_for "fab-a 照常生效" synced fab-a
assert_consistent fab-a
wait_for "fab-b 的 outbox 事件退避重試" retrying fab-b
check "fab-b 落後（applied 沒追上 desired）" not synced fab-b
service start cluster-api-b
wait_for "cluster-api-b 恢復後追到最新版本" synced fab-b
assert_consistent fab-b

CURRENT=4; scenario "4. Doris 掛掉：停掉 fe-b 後改 fab-b"
service stop fe-b
add_grant e2e-a fab-b sellers
wait_for "Cluster 回報連不到 Doris" output_has "doris-fab-b" status fab-b lastError
check "fab-b 落後" not synced fab-b
service start fe-b
wait_for "fe-b 恢復後自動補上" synced fab-b
assert_consistent fab-b

CURRENT=5; scenario "5. 錯誤的授權：不存在的 table，同 fab 另一個 SA 照常生效"
add_grant e2e-a fab-a nope
open_path e2e-b fab-a
add_grant e2e-b fab-a sellers
wait_for "e2e-b 的授權照常生效" output_has "olist.sellers" doris_grants fab-a sa_e2e_b
wait_for "lastError 說明 table 不存在" output_has "nope does not exist" status fab-a lastError
check "fab-a 的 applied 不前進" not synced fab-a
remove_grant e2e-a fab-a nope
wait_for "移除錯誤授權後追上" synced fab-a
assert_consistent fab-a

CURRENT=6; scenario "6. 權限漂移：用 root 手動改 managed SA，再觸發同步"
doris fab-a -e "GRANT LOAD_PRIV ON internal.olist.orders TO 'sa_e2e_a'@'%'; REVOKE SELECT_PRIV ON internal.olist.orders FROM 'sa_e2e_a'@'%'"
check "手動修改後確實不一致" not consistent fab-a
post /api/sync/fab-a >/dev/null
wait_for "修回與 Console 一致" consistent fab-a
wait_for "Cluster 回報漂移數 2" output_is 2 status fab-a lastDrift
doris fab-a -e "DROP USER 'sa_e2e_b'@'%'"
post /api/sync/fab-a >/dev/null
wait_for "被手動 DROP 的 SA 重建" consistent fab-a
check "重建後仍可用 Redis 帳密登入" output_is 3095 as_sa fab-a sa_e2e_b "$(password e2e-b fab-a)" "select count(*) from olist.sellers"

CURRENT=8; scenario "8. 冪等：重複觸發同一個版本"
before=$(status fab-a updatedAt)
post /api/sync/fab-a >/dev/null
post /api/sync/fab-a >/dev/null
wait_for "Cluster 重新套用" not output_is "$before" status fab-a updatedAt
wait_for "outbox 清空" synced fab-a
check "沒有執行任何 SQL（lastDrift 0）" output_is 0 status fab-a lastDrift
check "沒有錯誤" output_is "" status fab-a lastError
assert_consistent fab-a

CURRENT=9; scenario "9. 複製授權：fab-a → fab-b"
post "/workspaces/e2e-a/fabs/fab-a/sas/$(sa_id e2e-a fab-a)/grants/copy" -d "targets=$(sa_id e2e-a fab-b)" >/dev/null
wait_for "fab-b 套用到最新版本" synced fab-b
assert_consistent fab-b
check "fab-a 的授權（orders、customers）都複製到 fab-b" has_grants e2e-a fab-b orders customers
check "fab-b 原本多的授權（olist.*、sellers）保留" has_grants e2e-a fab-b '*' sellers

CURRENT=10; scenario "2 + 10. 撤銷，以及撤銷對既有連線的影響"
PW=$(password e2e-a fab-a)
SA=$(sa_id e2e-a fab-a)
open_session fab-a sa_e2e_a "$PW"
check "既有連線：撤銷前可查 orders" output_has 99441 in_session "select count(*) from olist.orders"
remove_grant e2e-a fab-a orders
wait_for "REVOKE 套用" synced fab-a
check "既有連線：REVOKE 立即生效，下一條查詢被拒" output_has "Access denied" in_session "select count(*) from olist.orders"
remove_grant e2e-a fab-a customers
clear_path e2e-a fab-a
wait_for "刪除 SA 後 DROP USER 套用" synced fab-a
assert_consistent fab-a
check "既有連線：DROP USER 後查任何 table 都被拒" output_has "Access denied" in_session "select count(*) from olist.customers"
check "既有連線：沒有被踢掉，不碰 table 的查詢仍可執行" output_has 42 in_session "select 42"
check "舊密碼無法建立新連線" output_has "Access denied" as_sa fab-a sa_e2e_a "$PW" "select 1"
check "Redis 的 cred key 已刪除" output_is "" redis-cli -h redis-a GET "doris:doris-fab-a:cred:$SA"
eval "exec ${SESSION[1]}>&-"

CURRENT=11; scenario "11. 重新產生密碼：舊密碼失效，DAO 從 Redis 拿到新密碼"
OLD_PW=$(password e2e-b fab-a)
OLD_VERSION=$(redis-cli -h redis-a GET doris:doris-fab-a:cred_version)
post "/workspaces/e2e-b/fabs/fab-a/sas/$(sa_id e2e-b fab-a)/password" >/dev/null
wait_for "新密碼套用" synced fab-a
NEW_PW=$(password e2e-b fab-a)
check "Redis 的密碼已更新" not output_is "$OLD_PW" echo "$NEW_PW"
check "cred_version 遞增" not output_is "$OLD_VERSION" redis-cli -h redis-a GET doris:doris-fab-a:cred_version
check "新密碼可以登入" output_is 3095 as_sa fab-a sa_e2e_b "$NEW_PW" "select count(*) from olist.sellers"
check "舊密碼無法登入" output_has "Access denied" as_sa fab-a sa_e2e_b "$OLD_PW" "select 1"
assert_consistent fab-a

CURRENT=7; scenario "7. 不碰人工帳號：跑完所有情境後 sa_manual 不變"
check "sa_manual 的帳號與權限都沒變" output_is "$MANUAL_BEFORE" doris_grants fab-a sa_manual

# ---------- summary ----------

trap - EXIT
cleanup
for fab in fab-a fab-b; do wait_for "清理後 $fab 同步" synced "$fab" >/dev/null; done
console_sql "delete from workspaces where workspace_name in ('e2e-a', 'e2e-b')"

echo
if (( ${#FAILED[@]} == 0 )); then
  echo "PASS：${PASSED} 項檢查全部通過"
else
  echo "FAIL：${#FAILED[@]} 項失敗，${PASSED} 項通過"
  printf '  - 情境 %s\n' "${FAILED[@]}"
  exit 1
fi
