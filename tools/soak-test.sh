#!/bin/bash
# 压力浸泡测试：反复循环运行机器操作 + 不变量断言，直到无可检测 bug。
# 不变量：
#   I1  涡轮守恒：S + W + 已排水量 == 累计灌入蒸汽量（冷凝 1:1，与冷凝进度无关）
#   I2  状态持久：区块卸载/重载后水位、储能、active 精确恢复
#   I3  状态机：toggle 每次都精确生效
#   I4  反应堆：蒸汽增量 == 0.85 × 实际消耗水量（±200）且蒸汽增量 ≤ 消耗水量
#   I5  容量钳制：任何时刻罐内容量不越界（由 fill 接受量间接验证）
#   I6  废液隔离：蒸汽罐空时 drain(int) 不得抽出废液；drain(FluidStack) 守恒
# 前提：服务器已启动且 RCON 25575 可用；测试机器位于 2950/2954/2974 @ y=100 z=2950。
cd "$(dirname "$0")/.." || exit 1
set -eE -o pipefail
shopt -s inherit_errexit
trap 'echo "FAIL: test aborted at line $LINENO" >&2' ERR

RCON() {
    local command="$1"
    local attempt response status
    for attempt in 1 2 3; do
        status=0
        response="$(powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/rcon-client.ps1 25575 cer 127.0.0.1 "$command" | tr -d '\r')" || status=$?
        response="$(printf '%s\n' "$response" | sed '/^> /d')"
        if [[ "$response" == *"RCON_ERROR:"* && "$response" == *"Connect"* ]]; then
            sleep 0.5
            continue
        fi
        if [[ "$status" == 0 && -n "$response" && "$response" != *"RCON_ERROR:"* && "$response" != *"NO_RESPONSE:"* && "$response" != *"AUTH_FAILED:"* ]]; then
            case "$command" in
                cerdev\ *) [[ "$response" == *"[cerdev] "* ]] || break ;;
                setblock\ *) [[ "$response" == *"Changed the block at "* ]] || break ;;
                forceload\ add\ *) [[ "$response" == *"Marked chunk "* || "$response" == *"Marked chunks "* ]] || break ;;
                forceload\ remove\ *) [[ "$response" == *"Unmarked chunk "* || "$response" == *"Unmarked chunks "* ]] || break ;;
                time\ query\ gametime) [[ "$response" =~ The\ time\ is\ [0-9]+ ]] || break ;;
                execute\ *) [[ "$response" == "Test passed" ]] || break ;;
                *) break ;;
            esac
            printf '%s\n' "$response"
            return 0
        fi
        break
    done
    printf '%s\n' "$response" >&2
    return 1
}
DUMP() {
    local response
    response="$(RCON "cerdev dump $1")" || return 1
    [[ "$response" == *"=== end dump ==="* ]] || return 1
    printf '%s\n' "$response" | grep '^\[cerdev\]'
}
TANK() {
    local response value
    response="$(DUMP "$1")" || return 1
    value="$(printf '%s\n' "$response" | sed -nE "s/^\[cerdev\] worldTank\[$2\/[0-9]+\]: .* x([0-9]+) .*/\1/p")"
    [[ "$value" =~ ^[0-9]+$ ]] || return 1
    printf '%s\n' "$value"
}
FIELD() {
    local response value
    response="$(DUMP "$1")" || return 1
    value="$(printf '%s\n' "$response" | grep -oE "(^| )$2=[^ ]+" | head -1 | cut -d= -f2)" || return 1
    [[ -n "$value" ]] || return 1
    printf '%s\n' "$value"
}
FE() { DUMP "$1" | grep -oE 'feStored=[0-9]+' | grep -oE '[0-9]+'; }
CHUNK_LOADED() {
    local response
    response="$(RCON "cerdev chunkstate $1")" || return 1
    [[ "$response" =~ ^\[cerdev\]\ chunkLoaded=(true|false)\ blockTicking=(true|false)$ ]] || return 1
    printf '%s\n' "${BASH_REMATCH[1]}"
}
NUMBER_FROM_TIME() { printf '%s\n' "$1" | sed -nE 's/.*The time is ([0-9]+).*/\1/p' | tail -1; }
FILL_ACCEPTED() { printf '%s\n' "$1" | grep '^\[cerdev\] fill ' | grep -oE '[0-9]+ mB' | tail -1 | cut -d' ' -f1; }
DRAIN_AMOUNT() { printf '%s\n' "$1" | sed -nE 's/^\[cerdev\] drain: .* x([0-9]+) mB.*/\1/p' | tail -1; }

PASS=0; FAIL=0; SKIP=0
check() { # check <名称> <期望> <实际>
    if [[ -n "$2" && -n "$3" && "$2" == "$3" ]]; then
        PASS=$((PASS+1)); echo "  PASS  $1 = $3"
    else
        FAIL=$((FAIL+1)); echo "  FAIL  $1 期望=$2 实际=$3"
    fi
}

TURB="2950 100 2950"
REAC="2954 100 2950"
SPARE="2974 100 2950"

echo "===== P1 区块卸载/重载 x6 ====="
REAC_S0="$(TANK "$REAC" 1)"
TURB_S0="$(TANK "$TURB" 1)"
echo "  基线: 反应堆蒸汽=$REAC_S0 涡轮水=$TURB_S0"
for i in 1 2 3 4 5 6; do
    RCON "forceload remove 2950 2950" > /dev/null
    RCON "forceload remove 2965 2950" > /dev/null
    unloaded=false
    for attempt in $(seq 1 60); do
        if [[ "$(CHUNK_LOADED "$TURB")" == false && "$(CHUNK_LOADED "$SPARE")" == false ]]; then
            unloaded=true
            break
        fi
        sleep 1
    done
    [[ "$unloaded" == true ]] || { echo "FAIL: chunks did not unload within 60 seconds"; exit 1; }
    RCON "forceload add 2950 2950" > /dev/null
    RCON "forceload add 2965 2950" > /dev/null
    sleep 2
    RCON "execute if loaded 2950 100 2950 if loaded 2974 100 2950" > /dev/null
    check "P1.$i 涡轮区块重新加载" true "$(CHUNK_LOADED "$TURB")"
    check "P1.$i 备用区块重新加载" true "$(CHUNK_LOADED "$SPARE")"
    check "P1.$i 涡轮控制器恢复" false "$(FIELD "$TURB" 'initFailed')"
    check "P1.$i 涡轮水位恢复" "$TURB_S0" "$(TANK "$TURB" 1)"
    check "P1.$i 涡轮 active 恢复" true "$(FIELD "$TURB" 'active')"
    check "P1.$i 反应堆蒸汽保持" "$REAC_S0" "$(TANK "$REAC" 1)"
done

echo "===== P2 方块破坏/重建 + 容量钳制 ====="
RCON "setblock 2974 100 2950 air" > /dev/null
sleep 1
RCON "setblock 2974 100 2950 compactextremereactor:compact_turbine" > /dev/null
sleep 2
check "P2 新建蒸汽" 0 "$(TANK "$SPARE" 0)"
check "P2 新建水" 0 "$(TANK "$SPARE" 1)"
fill_response="$(RCON "cerdev fill $SPARE bigreactors:steam 20000")" || exit 1
ACC="$(FILL_ACCEPTED "$fill_response")"
[[ "$ACC" =~ ^[0-9]+$ ]] || { echo "  FAIL  P2 无法解析 fill 响应: $fill_response"; exit 1; }
check "P2 容量钳制: 超灌20000只收10000" 10000 "$ACC"
sleep 4
check "P2 全额冷凝为水" 10000 "$(TANK "$SPARE" 1)"
RCON "cerdev drain $SPARE 20000" > /dev/null
sleep 1
check "P2 排空后水" 0 "$(TANK "$SPARE" 1)"
RCON "setblock 2974 100 2950 air" > /dev/null
sleep 1
RCON "setblock 2974 100 2950 compactextremereactor:compact_turbine" > /dev/null
sleep 2
check "P2 重建后蒸汽" 0 "$(TANK "$SPARE" 0)"
check "P2 重建后水" 0 "$(TANK "$SPARE" 1)"
check "P2 重建后 initFailed" false "$(FIELD "$SPARE" 'initFailed')"
check "P2 主涡轮不受影响" "$TURB_S0" "$(TANK "$TURB" 1)"

echo "===== P3 toggle 连打 x40 ====="
BAD=0
for i in $(seq 1 40); do
    if [ $((i % 2)) -eq 1 ]; then want=true; else want=false; fi
    if ! RCON "cerdev active $SPARE $want" > /dev/null; then
        BAD=$((BAD+1))
        continue
    fi
    got="$(FIELD "$SPARE" 'active')"
    [ "$got" == "$want" ] || { BAD=$((BAD+1)); echo "  第 $i 次翻转失败: 期望 $want 实际 $got"; }
    sleep 0.4
done
check "P3 toggle 失败次数" 0 "$BAD"

echo "===== P4 灌排锤击 x15（不变量 I1） ====="
S0="$(TANK "$SPARE" 0)"; W0="$(TANK "$SPARE" 1)"
I=$((S0 + W0)); D=0; VIOL=0
for i in $(seq 1 15); do
    W0="$(TANK "$SPARE" 1)"; S0="$(TANK "$SPARE" 0)"
    drain_response="$(RCON "cerdev drain $SPARE 10000")" || exit 1
    drained="$(DRAIN_AMOUNT "$drain_response")"
    if [[ -z "$drained" && "$drain_response" != *"[cerdev] drain:"* ]]; then
        echo "  FAIL  P4 无法解析 drain 响应: $drain_response"
        exit 1
    fi
    drained="${drained:-0}"
    D=$((D + drained))
    A=$(( (i % 5 + 1) * 1000 ))
    fill_response="$(RCON "cerdev fill $SPARE bigreactors:steam $A")" || exit 1
    acc="$(FILL_ACCEPTED "$fill_response")"
    [[ "$acc" =~ ^[0-9]+$ ]] || { echo "  FAIL  P4 无法解析 fill 响应: $fill_response"; exit 1; }
    I=$((I + acc))
    sleep 1
    steam="$(TANK "$SPARE" 0)"; water="$(TANK "$SPARE" 1)"
    T=$((steam + water))
    EXP=$((S0 + W0 - drained + acc))
    [ "$T" == "$EXP" ] || { VIOL=$((VIOL+1)); echo "  第 $i 轮破坏守恒: S+W=$T 期望=$EXP (acc=$acc drained=$drained S0=$S0 W0=$W0)"; }
done
check "P4 守恒破坏次数" 0 "$VIOL"
FW="$(TANK "$SPARE" 1)"; FS="$(TANK "$SPARE" 0)"
check "P4 全程总守恒 初始量+I-D==S+W" "$((I - D))" "$((FW + FS))"

echo "===== P5 反应堆水→蒸汽浸泡 x10（不变量 I4'：0.85 产出律 + 无中生有防护 + 排空完备） ====="
# 旧断言（产出 4200~4250）是"热耗尽指纹"，依赖燃料纯度/堆芯热状态：新燃料高纯度时
# 8s 可把 5000 水全部汽化，退化燃料+低堆芯热时只能产出 ~800（均为上游合法热力学）。
# 状态无关的 mod/上游不变量（字节码 FluidContainer.vaporize 确认）：
#   a) 无中生有防护：蒸汽增量 <= 灌入水量
#   b) 0.85 产出律：蒸汽增量 == 0.85 × 实际消耗水量（每 tick floor 舍入，容差 200 > 160 tick）
#   c) 排空完备：drain 200000 后蒸汽罐归零；冷却水按契约只进不出（fill-only），
#      drain 不得改动水罐（W2 == W1）
VIOL=0
total_consumed=0
for i in $(seq 1 10); do
    RCON "cerdev active $REAC false" > /dev/null
    RCON "cerdev energy $REAC extract 1000000" > /dev/null   # 留 FE 空位，防满仓停产干扰测量
    RCON "cerdev drain $REAC 200000" > /dev/null   # 预排空蒸汽
    sleep 1
    G0="$(TANK "$REAC" 1)"; W0="$(TANK "$REAC" 0)"
    fill_response="$(RCON "cerdev fill $REAC minecraft:water 5000")" || exit 1
    ACC="$(FILL_ACCEPTED "$fill_response")"
    [[ "$ACC" =~ ^[0-9]+$ ]] || { echo "  FAIL  P5 无法解析 fill 响应: $fill_response"; exit 1; }
    RCON "cerdev active $REAC true" > /dev/null
    sleep 8
    RCON "cerdev active $REAC false" > /dev/null
    G1="$(TANK "$REAC" 1)"; W1="$(TANK "$REAC" 0)"
    RCON "cerdev drain $REAC 200000" > /dev/null
    G2="$(TANK "$REAC" 1)"; W2="$(TANK "$REAC" 0)"
    D=$((G1 - G0))            # 蒸汽增量
    C=$((W0 + ACC - W1))      # 实际消耗水量（按实际接受量计，含上轮剩水）
    total_consumed=$((total_consumed + C))
    echo "  P5.$i accepted=$ACC consumed=$C produced=$D waterBeforeDrain=$W1 waterAfterDrain=$W2"
    BAD=""
    [ "$D" -gt "$C" ] && BAD="无中生有 D=$D>C=$C"
    LAW="$(awk -v c="$C" -v d="$D" 'BEGIN{lo=0.85*c-200; hi=0.85*c+1; print (d>=lo && d<=hi) ? "ok" : "bad"}')"
    [ "$LAW" == "ok" ] || BAD="$BAD 0.85律破坏 D=$D C=$C"
    [ "$G2" != "0" ] && BAD="$BAD 蒸汽排空不完备 G2=$G2"
    [ "$W2" != "$W1" ] && BAD="$BAD 冷却水被 drain 改动 W1=$W1 W2=$W2"
    if [ -n "$BAD" ]; then VIOL=$((VIOL+1)); echo "  第 $i 轮: $BAD"; fi
done
check "P5 违规次数" 0 "$VIOL"
check "P5 实际消耗冷却水" "ok" "$([ "$total_consumed" -gt 0 ] && echo ok || echo bad)"

echo "===== P7 废液隔离：无类型 drain 不得抽废液；类型 drain 守恒 ====="
RCON "cerdev active $REAC false" > /dev/null
sleep 1
RCON "cerdev drain $REAC 200000" > /dev/null
sleep 1
W0="$(FIELD "$REAC" 'waste')"
check "P7 冻堆后 waste 基线可读" "ok" "$([ -n "$W0" ] && echo ok || echo bad)"
UNT="$(RCON "cerdev drain $REAC 1000")"
echo "  untyped: $(echo "$UNT" | grep '^\[cerdev\]')"
if echo "$UNT" | grep -q 'bigreactors:cyanite\|bigreactors:magentite\|bigreactors:rossinite'; then
    FAIL=$((FAIL+1)); echo "  FAIL  P7 无类型 drain 抽出了废液"
else
    PASS=$((PASS+1)); echo "  PASS  P7 无类型 drain 未抽废液"
fi
W1="$(FIELD "$REAC" 'waste')"
check "P7 无类型 drain 不减少 waste" "$W0" "$W1"
RCON "cerdev waste $REAC inject 2000" > /dev/null
sleep 1
W2="$(FIELD "$REAC" 'waste')"
WASTE_TYPE="$(FIELD "$REAC" 'wasteReactant')"
if [[ ! "$W2" =~ ^[0-9]+$ || "$W2" -lt 1000 || "$WASTE_TYPE" == '-' ]]; then
    FAIL=$((FAIL+1))
    echo "  FAIL  P7 废物注入后前置状态异常: waste=$W2 wasteReactant=$WASTE_TYPE"
elif [[ "$WASTE_TYPE" == 'cyanite' || "$WASTE_TYPE" == 'bigreactors:cyanite' ]]; then
    TYP="$(RCON "cerdev drain $REAC 1000 bigreactors:cyanite")"
    echo "  typed: $(echo "$TYP" | grep '^\[cerdev\]')"
    W3="$(FIELD "$REAC" 'waste')"
    check "P7 类型 drain 返回 cyanite 1000" "ok" "$(echo "$TYP" | grep -q 'cyanite x1000' && echo ok || echo bad)"
    check "P7 类型 drain 1000 后 waste" "$((W2 - 1000))" "$W3"
else
    echo "  SKIP  P7 当前废物为 $WASTE_TYPE，非 cyanite"
    SKIP=$((SKIP+1))
fi
RCON "cerdev active $REAC true" > /dev/null

echo "===== P8 FE 满仓自动停机：不耗蒸汽、active 不变、抽出后恢复 ====="
# P3 以 active=false 收尾，必须先打开。先灌满 FE 再灌蒸汽：暂停期间蒸汽原样保留，抽出后才应进汽。
RCON "cerdev active $SPARE true" > /dev/null
RCON "cerdev drain $SPARE 200000" > /dev/null
sleep 1
RCON "cerdev energy $SPARE fill" > /dev/null
sleep 1
check "P8 满仓 energyFull" true "$(FIELD "$SPARE" 'energyFull')"
check "P8 满仓 active 仍 true" true "$(FIELD "$SPARE" 'active')"
RCON "cerdev fill $SPARE bigreactors:steam 5000" > /dev/null
sleep 1
S1="$(TANK "$SPARE" 0)"; W1="$(TANK "$SPARE" 1)"
sleep 3
S2="$(TANK "$SPARE" 0)"; W2="$(TANK "$SPARE" 1)"
check "P8 满仓蒸汽冻结" "$S1" "$S2"
check "P8 满仓水位冻结" "$W1" "$W2"
RCON "cerdev energy $SPARE extract 1000000" > /dev/null
sleep 1
check "P8 抽出后 energyFull" false "$(FIELD "$SPARE" 'energyFull')"
check "P8 抽出后 active 仍 true" true "$(FIELD "$SPARE" 'active')"
sleep 3
S3="$(TANK "$SPARE" 0)"
if [ -n "$S3" ] && [ -n "$S2" ] && [ "$S3" -lt "$S2" ]; then
    PASS=$((PASS+1)); echo "  PASS  P8 抽出后恢复进汽 S2=$S2 S3=$S3"
else
    FAIL=$((FAIL+1)); echo "  FAIL  P8 抽出后未恢复进汽 S2=$S2 S3=$S3"
fi
RCON "cerdev drain $SPARE 200000" > /dev/null

echo "===== P6 TPS 终测 ====="
T0="$(NUMBER_FROM_TIME "$(RCON "time query gametime")")"
started=$(date +%s%N)
sleep 30
T1="$(NUMBER_FROM_TIME "$(RCON "time query gametime")")"
finished=$(date +%s%N)
if ! [[ "$T0" =~ ^[0-9]+$ && "$T1" =~ ^[0-9]+$ && "$T1" -ge "$T0" ]]; then
    echo "  FAIL  P6 time query parsing: T0=$T0 T1=$T1"
    exit 1
fi
elapsed=$((finished - started))
[[ "$elapsed" -gt 0 ]] || exit 1
TPS="$(awk -v a="$T0" -v b="$T1" -v elapsed="$elapsed" 'BEGIN{printf "%.3f", (b-a)*1000000000/elapsed}')"
echo "  TPS estimate = $TPS (includes RCON query latency)"
check "P6 TPS >= 19" "ok" "$(awk -v tps="$TPS" 'BEGIN{print tps>=19 ? "ok" : "bad"}')"

echo "========================================="
echo "结果: PASS=$PASS FAIL=$FAIL SKIP=$SKIP"
exit "$FAIL"
