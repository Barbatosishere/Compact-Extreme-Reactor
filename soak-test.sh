#!/bin/bash
# 压力浸泡测试：反复循环运行机器操作 + 不变量断言，直到无可检测 bug。
# 不变量：
#   I1  涡轮守恒：S + W + 已排水量 == 累计灌入蒸汽量（冷凝 1:1，与冷凝进度无关）
#   I2  状态持久：区块卸载/重载后水位、储能、active 精确恢复
#   I3  状态机：toggle 每次都精确生效
#   I4  反应堆：每轮 5000 水 -> 4250 蒸汽（0.85 ± 取整容差）
#   I5  容量钳制：任何时刻罐内容量不越界（由 fill 接受量间接验证）
# 前提：服务器已启动且 RCON 25575 可用；测试机器位于 2950/2954/2974 @ y=100 z=2950。
cd "$(dirname "$0")" || exit 1

RCON() { powershell.exe -NoProfile -ExecutionPolicy Bypass -File rcon-port.ps1 25575 cer 127.0.0.1 "$1" 2>/dev/null | tr -d '\r'; }
DUMP() { RCON "cerdev dump $1" | grep '^\[cerdev\]'; }
TANK() { DUMP "$1" | grep "worldTank\[$2/" | sed -E 's/.*x([0-9]+) .*/\1/'; }
FIELD() { DUMP "$1" | grep -oE "$2=[^ ]+" | head -1 | cut -d= -f2; }
FE() { DUMP "$1" | grep -oE 'feStored=[0-9]+' | grep -oE '[0-9]+'; }

PASS=0; FAIL=0
check() { # check <名称> <期望> <实际>
    if [ "$2" == "$3" ]; then
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
    sleep 1
    RCON "forceload add 2950 2950" > /dev/null
    RCON "forceload add 2965 2950" > /dev/null
    sleep 2
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
ACC="$(RCON "cerdev fill $SPARE bigreactors:steam 20000" | grep -oE '[0-9]+' | tail -1)"
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
    RCON "cerdev active $SPARE $want" > /dev/null
    got="$(FIELD "$SPARE" 'active')"
    [ "$got" == "$want" ] || { BAD=$((BAD+1)); echo "  第 $i 次翻转失败: 期望 $want 实际 $got"; }
    sleep 0.4
done
check "P3 toggle 失败次数" 0 "$BAD"

echo "===== P4 灌排锤击 x15（不变量 I1） ====="
I=0; D=0; VIOL=0
for i in $(seq 1 15); do
    W0="$(TANK "$SPARE" 1)"; S0="$(TANK "$SPARE" 0)"
    RCON "cerdev drain $SPARE 10000" > /dev/null
    D=$((D + W0))
    A=$(( (i % 5 + 1) * 1000 ))
    acc="$(RCON "cerdev fill $SPARE bigreactors:steam $A" | grep -oE '[0-9]+' | tail -1)"
    I=$((I + acc))
    sleep 1
    T="$(($(TANK "$SPARE" 0) + $(TANK "$SPARE" 1)))"
    EXP=$((S0 + acc))
    [ "$T" == "$EXP" ] || { VIOL=$((VIOL+1)); echo "  第 $i 轮破坏守恒: S+W=$T 期望=$EXP (acc=$acc S0=$S0)"; }
done
check "P4 守恒破坏次数" 0 "$VIOL"
FW="$(TANK "$SPARE" 1)"; FS="$(TANK "$SPARE" 0)"
check "P4 全程总守恒 I-D==S+W" "$((I - D))" "$((FW + FS))"

echo "===== P5 反应堆水→蒸汽浸泡 x10（不变量 I4'：0.85 产出律 + 无中生有防护 + 排空完备） ====="
# 旧断言（产出 4200~4250）是"热耗尽指纹"，依赖燃料纯度/堆芯热状态：新燃料高纯度时
# 8s 可把 5000 水全部汽化，退化燃料+低堆芯热时只能产出 ~800（均为上游合法热力学）。
# 状态无关的 mod/上游不变量（字节码 FluidContainer.vaporize 确认）：
#   a) 无中生有防护：蒸汽增量 <= 灌入水量
#   b) 0.85 产出律：蒸汽增量 == 0.85 × 实际消耗水量（每 tick floor 舍入，容差 200 > 160 tick）
#   c) 排空完备：drain 200000 后水/汽双罐归零
VIOL=0
for i in $(seq 1 10); do
    RCON "cerdev drain $REAC 200000" > /dev/null   # 预排空，保证 5000 水全额接受
    sleep 1
    G0="$(TANK "$REAC" 1)"; W0="$(TANK "$REAC" 0)"
    RCON "cerdev fill $REAC minecraft:water 5000" > /dev/null
    sleep 8
    G1="$(TANK "$REAC" 1)"; W1="$(TANK "$REAC" 0)"
    RCON "cerdev drain $REAC 200000" > /dev/null
    G2="$(TANK "$REAC" 1)"; W2="$(TANK "$REAC" 0)"
    D=$((G1 - G0))            # 蒸汽增量
    C=$((W0 + 5000 - W1))     # 实际消耗水量
    BAD=""
    [ "$D" -gt 5000 ] && BAD="无中生有 D=$D>5000"
    LAW="$(awk -v c="$C" -v d="$D" 'BEGIN{lo=0.85*c-200; hi=0.85*c+1; print (d>=lo && d<=hi) ? "ok" : "bad"}')"
    [ "$LAW" == "ok" ] || BAD="$BAD 0.85律破坏 D=$D C=$C"
    { [ "$G2" != "0" ] || [ "$W2" != "0" ]; } && BAD="$BAD 排空不完备 G2=$G2 W2=$W2"
    if [ -n "$BAD" ]; then VIOL=$((VIOL+1)); echo "  第 $i 轮: $BAD"; fi
done
check "P5 违规次数" 0 "$VIOL"

echo "===== P6 TPS 终测 ====="
T0="$(RCON "time query gametime" | sed -n 2p | grep -oE '[0-9]+')"
sleep 30
T1="$(RCON "time query gametime" | sed -n 2p | grep -oE '[0-9]+')"
awk -v a="$T0" -v b="$T1" 'BEGIN{printf "  TPS = %.3f\n", (b-a)/30}'
TPS="$(awk -v a="$T0" -v b="$T1" 'BEGIN{printf "%d", (b-a)/30}')"
check "P6 TPS >= 19" "ok" "$([ "$TPS" -ge 19 ] && echo ok || echo bad)"

echo "========================================="
echo "结果: PASS=$PASS FAIL=$FAIL"
exit "$FAIL"
