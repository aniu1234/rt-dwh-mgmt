#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/deploy/docker-compose.yml"
API_BASE="${RTDWH_API_BASE:-http://127.0.0.1:8080}"
ADMIN_USER="${RTDWH_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${RTDWH_ADMIN_PASSWORD:-}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root123123}"
MYSQL_USER="${MYSQL_USER:-rtdwh_admin}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root123123}"
TIMEOUT_SECONDS="${SMOKE_TIMEOUT_SECONDS:-180}"
TARGET_CATALOG="${DORIS_CATALOG:-rtdwh_paimon}"
TARGET_DATABASE="${DORIS_DATABASE:-ods}"
SOURCE_DATABASE="rtdwh_smoke"
SOURCE_TABLE="cdc_events"
TARGET_TABLE="rtdwh_smoke_events"
RUN_ID="$(date +%s)"
PAYLOAD="smoke-${RUN_ID}"

TOKEN=""
SOURCE_ID=""
TARGET_ID=""
TASK_ID=""

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

for command in curl jq docker; do
  command -v "$command" >/dev/null 2>&1 || fail "缺少命令: $command"
done

[[ -n "$ADMIN_PASSWORD" ]] || fail "请通过 RTDWH_ADMIN_PASSWORD 提供平台管理员密码"

COMPOSE=(docker compose -f "$COMPOSE_FILE")
if [[ -f "$ROOT_DIR/deploy/.env" ]]; then
  COMPOSE=(docker compose --env-file "$ROOT_DIR/deploy/.env" -f "$COMPOSE_FILE")
fi

api() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local args=(-fsS --max-time 120 -X "$method" "$API_BASE$path")
  [[ -n "$TOKEN" ]] && args+=(-H "Authorization: Bearer $TOKEN")
  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$body")
  fi
  curl "${args[@]}"
}

require_success() {
  local response="$1"
  local label="$2"
  local code
  code="$(jq -r '.code // -1' <<<"$response")"
  [[ "$code" == "0" ]] || fail "$label 失败: $(jq -r '.message // . ' <<<"$response")"
}

cleanup() {
  set +e
  if [[ -n "$TASK_ID" && -n "$TOKEN" ]]; then
    api POST "/sync-tasks/$TASK_ID/stop" >/dev/null 2>&1
    api DELETE "/sync-tasks/$TASK_ID" >/dev/null 2>&1
  fi
  if [[ -n "$SOURCE_ID" && -n "$TOKEN" ]]; then
    api DELETE "/datasources/$SOURCE_ID" >/dev/null 2>&1
  fi
  if [[ -n "$TARGET_ID" && -n "$TOKEN" ]]; then
    api DELETE "/datasources/$TARGET_ID" >/dev/null 2>&1
  fi
}
trap cleanup EXIT

printf '[1/7] 检查平台和基础组件...\n'
curl -fsS --max-time 10 "$API_BASE/actuator/health" >/dev/null
RUNNING_COUNT="$("${COMPOSE[@]}" ps --services --status running \
  mysql flink-jobmanager flink-taskmanager flink-sql-gateway doris-fe doris-be | wc -l | tr -d ' ')"
[[ "$RUNNING_COUNT" == "6" ]] || fail "MySQL、Flink、SQL Gateway 或 Doris 容器未全部运行"

printf '[2/7] 登录管理平台...\n'
LOGIN_BODY="$(jq -nc --arg username "$ADMIN_USER" --arg password "$ADMIN_PASSWORD" \
  '{username:$username,password:$password}')"
LOGIN_RESPONSE="$(api POST /auth/login "$LOGIN_BODY")"
require_success "$LOGIN_RESPONSE" "登录"
TOKEN="$(jq -r '.data.token // empty' <<<"$LOGIN_RESPONSE")"
[[ -n "$TOKEN" ]] || fail "登录响应未返回 token"

printf '[3/7] 准备 MySQL CDC 源表和复制权限...\n'
SOURCE_SQL="CREATE DATABASE IF NOT EXISTS ${SOURCE_DATABASE};
CREATE TABLE IF NOT EXISTS ${SOURCE_DATABASE}.${SOURCE_TABLE} (
  id BIGINT PRIMARY KEY,
  payload VARCHAR(255) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '${MYSQL_USER}'@'%';
GRANT ALL PRIVILEGES ON ${SOURCE_DATABASE}.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;"
"${COMPOSE[@]}" exec -T mysql mysql -uroot "-p${MYSQL_ROOT_PASSWORD}" -e "$SOURCE_SQL"

printf '[4/7] 创建临时数据源配置...\n'
SOURCE_BODY="$(jq -nc \
  --arg name "smoke-mysql-${RUN_ID}" --arg database "$SOURCE_DATABASE" \
  --arg username "$MYSQL_USER" --arg password "$MYSQL_PASSWORD" \
  '{configName:$name,dbType:"mysql",host:"mysql",port:3306,database:$database,username:$username,passwordEncrypted:$password,extraParams:"{}"}')"
SOURCE_RESPONSE="$(api POST /datasources "$SOURCE_BODY")"
require_success "$SOURCE_RESPONSE" "创建 MySQL 数据源"
SOURCE_ID="$(jq -r '.data.id' <<<"$SOURCE_RESPONSE")"

TARGET_BODY="$(jq -nc \
  --arg name "smoke-paimon-${RUN_ID}" --arg username "$MYSQL_USER" --arg password "$MYSQL_PASSWORD" \
  '{configName:$name,dbType:"paimon",host:"/data/paimon",port:3306,database:"rtdwh_paimon_meta",username:$username,passwordEncrypted:$password,extraParams:"{}"}')"
TARGET_RESPONSE="$(api POST /datasources "$TARGET_BODY")"
require_success "$TARGET_RESPONSE" "创建 Paimon 数据源"
TARGET_ID="$(jq -r '.data.id' <<<"$TARGET_RESPONSE")"

printf '[5/7] 创建并启动 CDC 任务...\n'
MAPPINGS="$(jq -nc --arg source "$SOURCE_TABLE" --arg db "$TARGET_DATABASE" --arg target "$TARGET_TABLE" \
  '[{sourceTable:$source,targetDb:$db,targetTable:$target,syncMode:"full+incremental"}]')"
TASK_BODY="$(jq -nc \
  --arg name "smoke-cdc-${RUN_ID}" --argjson sourceId "$SOURCE_ID" --argjson targetId "$TARGET_ID" \
  --arg mappings "$MAPPINGS" \
  '{taskName:$name,description:"CDC -> Paimon -> Doris smoke test",taskType:"cdc_sync",sourceConfigId:$sourceId,targetConfigId:$targetId,flinkSql:"-- generated when task starts",syncStrategy:"full_then_incremental",tableMappings:$mappings,parallelism:1,checkpointIntervalMs:5000}')"
TASK_RESPONSE="$(api POST /sync-tasks "$TASK_BODY")"
require_success "$TASK_RESPONSE" "创建 CDC 任务"
TASK_ID="$(jq -r '.data.id' <<<"$TASK_RESPONSE")"
START_RESPONSE="$(api POST "/sync-tasks/$TASK_ID/start")"
require_success "$START_RESPONSE" "启动 CDC 任务"
START_STATUS="$(jq -r '.data.status // empty' <<<"$START_RESPONSE")"
[[ "$START_STATUS" == "running" ]] || fail "CDC 任务未进入 running: $(jq -r '.data.lastErrorMsg // .message' <<<"$START_RESPONSE")"

printf '[6/7] 写入增量事件并等待 Paimon 提交...\n'
INSERT_SQL="INSERT INTO ${SOURCE_DATABASE}.${SOURCE_TABLE}(id,payload) VALUES (${RUN_ID},'${PAYLOAD}') ON DUPLICATE KEY UPDATE payload=VALUES(payload),updated_at=CURRENT_TIMESTAMP(3);"
"${COMPOSE[@]}" exec -T mysql mysql -u"$MYSQL_USER" "-p${MYSQL_PASSWORD}" -e "$INSERT_SQL"

printf '[7/7] 通过 Doris 查询 Paimon 快照...\n'
DEADLINE=$((SECONDS + TIMEOUT_SECONDS))
while (( SECONDS < DEADLINE )); do
  QUERY_BODY="$(jq -nc \
    --arg sql "SELECT id, payload FROM ${TARGET_TABLE} WHERE id = ${RUN_ID}" \
    --arg catalog "$TARGET_CATALOG" --arg database "$TARGET_DATABASE" \
    '{sql:$sql,maxRows:10,timeoutSeconds:15,catalog:$catalog,database:$database}')"
  QUERY_RESPONSE="$(api POST /query/execute "$QUERY_BODY" || true)"
  if [[ -n "$QUERY_RESPONSE" ]] \
      && [[ "$(jq -r '.code // -1' <<<"$QUERY_RESPONSE" 2>/dev/null)" == "0" ]] \
      && [[ "$(jq -r '.data.status // empty' <<<"$QUERY_RESPONSE")" == "success" ]] \
      && jq -e --arg payload "$PAYLOAD" '.data.rows[]? | .[1] == $payload' <<<"$QUERY_RESPONSE" >/dev/null; then
    printf 'PASS: MySQL CDC -> Paimon -> Doris 链路验证成功，id=%s, payload=%s\n' "$RUN_ID" "$PAYLOAD"
    exit 0
  fi
  sleep 5
done

fail "等待 ${TIMEOUT_SECONDS}s 后 Doris 仍未查询到增量事件"
