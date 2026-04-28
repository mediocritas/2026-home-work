#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# Нагрузочное тестирование задания N (gRPC):
# сравнение внутреннего транспорта HTTP vs gRPC.
#
# Сравниваем при равных условиях:
#   - один и тот же кластер mediocritas (PushkinaKVCluster)
#   - те же ноды (порты 8080, 8081) и те же lua-сценарии PUT/GET
#   - меняется только -Dproxy.client.type=http|grpc
#
# Запуск: ./load_test.sh
# ============================================================

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS_DIR="$PROJECT_DIR/wrk-scripts"
RESULTS_DIR="$PROJECT_DIR/wrk-results"
PORT=8080
HOST="host.docker.internal"   # Mac + Docker Desktop
WRK_IMAGE="wrk2-local"
# больше соединений — реальная разница HTTP vs gRPC видна именно под нагрузкой
WRK_PARAMS="-t2 -c64 -R500 -d30s --latency"
SERVER_PID=""

MAIN_CLASS="company.vk.edu.distrib.compute.mediocritas.BenchmarkServer"

# ── Цвета ───────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; NC='\033[0m'

log()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()   { echo -e "${GREEN}[OK]${NC}    $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()  { echo -e "${RED}[ERROR]${NC} $*"; }

# ── Очистка при выходе ──────────────────────────────────────
cleanup() {
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        log "Останавливаю сервер (PID=$SERVER_PID)..."
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
        ok "Сервер остановлен"
    fi
}
trap cleanup EXIT

# ============================================================
# 1. Lua-скрипты (один PUT, один GET — общие для обоих транспортов)
# ============================================================
create_lua_scripts() {
    mkdir -p "$SCRIPTS_DIR"
    log "Создаю Lua-скрипты в $SCRIPTS_DIR ..."

    cat > "$SCRIPTS_DIR/put.lua" << 'EOF'
counter = 0
request = function()
    counter = counter + 1
    local key  = "key" .. counter
    local body = "value" .. counter
    local headers = {}
    headers["Content-Type"] = "application/octet-stream"
    return wrk.format("PUT", "/v0/entity?id=" .. key, headers, body)
end
EOF

    cat > "$SCRIPTS_DIR/get.lua" << 'EOF'
counter = 0
request = function()
    counter = counter + 1
    local key = "key" .. counter
    return wrk.format("GET", "/v0/entity?id=" .. key)
end
EOF

    ok "Создано 2 Lua-скрипта: put.lua, get.lua"
}

# ============================================================
# 2. Docker-образ wrk2
# ============================================================
build_docker() {
    if docker image inspect "$WRK_IMAGE" &>/dev/null; then
        ok "Docker-образ '$WRK_IMAGE' уже существует, пропускаю сборку"
        return
    fi
    log "Собираю Docker-образ '$WRK_IMAGE' (это займёт несколько минут)..."
    docker build -t "$WRK_IMAGE" "$PROJECT_DIR"
    ok "Docker-образ собран"
}

# ============================================================
# 3. Сборка проекта (без тестов и стиль-чеков)
# ============================================================
build_project() {
    log "Собираю проект (./gradlew build -x test)..."
    cd "$PROJECT_DIR"
    ./gradlew build -x test -x integrationTest \
        -x checkstyleMain -x checkstyleIntegrationTest \
        -x pmdMain -x pmdIntegrationTest --quiet
    ok "Проект собран"
}

# ============================================================
# 4. Запуск кластера в нужном транспортном режиме
#    $1 = http | grpc
# ============================================================
start_server() {
    local transport="$1"

    log "Очищаю старые данные кластера..."
    rm -rf "$PROJECT_DIR"/data-cluster-* 2>/dev/null || true

    log "Запускаю BenchmarkServer (transport=$transport, ports=8080,8081)..."
    cd "$PROJECT_DIR"

    # запускаем main-класс через gradle JavaExec, чтобы пробросить sysprop
    ./gradlew --quiet -PmainClass="$MAIN_CLASS" \
        run --args="" \
        -Dproxy.client.type="$transport" \
        > "/tmp/server_${transport}.log" 2>&1 &
    SERVER_PID=$!

    log "Жду пока сервер поднимется (PID=$SERVER_PID)..."
    local attempts=0
    until curl -sf "http://localhost:${PORT}/v0/status" -o /dev/null 2>/dev/null; do
        sleep 1
        attempts=$((attempts + 1))
        if [[ $attempts -ge 60 ]]; then
            err "Сервер не поднялся за 60 секунд. Лог:"
            tail -40 "/tmp/server_${transport}.log"
            exit 1
        fi
        printf "."
    done
    echo ""
    ok "Сервер готов на порту $PORT (transport=$transport)"
}

stop_server() {
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        log "Останавливаю сервер (PID=$SERVER_PID)..."
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
        SERVER_PID=""
        ok "Сервер остановлен"
    fi
    sleep 2
}

# ============================================================
# 5. Запуск wrk2 через Docker
#    $1 = scenario  (put|get)
#    $2 = transport (http|grpc)
# ============================================================
run_wrk() {
    local scenario="$1"
    local transport="$2"
    local label
    label="$(echo "$scenario" | tr '[:lower:]' '[:upper:]') via $(echo "$transport" | tr '[:lower:]' '[:upper:]')"
    local result_file="$RESULTS_DIR/${scenario}_${transport}.hgrm"

    echo ""
    echo -e "${YELLOW}══════════════════════════════════════════${NC}"
    log "Тест: $label"
    log "Скрипт: ${scenario}.lua"
    log "Результат: ${result_file}"
    echo -e "${YELLOW}══════════════════════════════════════════${NC}"

    local full_output
    full_output=$(docker run --rm \
        -v "$SCRIPTS_DIR":/data \
        "$WRK_IMAGE" \
        $WRK_PARAMS \
        -s "/data/${scenario}.lua" \
        "http://${HOST}:${PORT}" 2>&1)

    echo "$full_output"

    # Сохраняем HDR-часть (после "Detailed Percentile spectrum:")
    echo "$full_output" \
        | awk '/Detailed Percentile spectrum:/,0 { if (!/Detailed Percentile spectrum:/) print }' \
        > "$result_file"

    if [[ -s "$result_file" ]]; then
        ok "HDR-данные сохранены → $result_file"
    else
        warn "HDR-данные не найдены в выводе wrk2 (файл пустой)"
    fi
}

# ============================================================
# 6. Полный прогон одного транспорта
#    $1 = http | grpc
# ============================================================
run_transport() {
    local transport="$1"

    local transport_upper
    transport_upper="$(echo "$transport" | tr '[:lower:]' '[:upper:]')"

    echo ""
    echo -e "${CYAN}╔══════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║  Транспорт: ${transport_upper}  ${NC}"
    echo -e "${CYAN}╚══════════════════════════════════════════╝${NC}"

    start_server "$transport"
    run_wrk "put" "$transport"
    run_wrk "get" "$transport"
    stop_server
}

# ============================================================
# MAIN
# ============================================================
main() {
    echo ""
    echo -e "${CYAN}╔══════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║  Сравнение транспорта HTTP vs gRPC       ║${NC}"
    echo -e "${CYAN}╚══════════════════════════════════════════╝${NC}"
    echo ""

    mkdir -p "$RESULTS_DIR"

    create_lua_scripts
    build_docker
    build_project

    run_transport "http"
    run_transport "grpc"

    echo ""
    echo -e "${GREEN}╔══════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║          Тестирование завершено!         ║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════════════╝${NC}"
    echo ""
    log "Результаты (.hgrm файлы):"
    ls -lh "$RESULTS_DIR"/*.hgrm 2>/dev/null || warn "Нет .hgrm файлов"
    echo ""
    ok "Загрузи .hgrm файлы парами на https://hdrhistogram.github.io/HdrHistogram/plotFiles.html"
    ok "Сравни: put_http.hgrm vs put_grpc.hgrm  и  get_http.hgrm vs get_grpc.hgrm"
    ok "Кнопка 'Export Image' — скриншот для PR (две линии на графике: HTTP и gRPC)"
}

main "$@"
