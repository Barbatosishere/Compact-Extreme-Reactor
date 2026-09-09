# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-beta17] - 2026-09-02

### Fixed

- **反应堆输入与容器交互** — 双端反应堆现在支持水/冷却液、黄铀液/钚液/蓝钚液等 Reactant 燃料流体，以及对应的手持流体容器；涡轮机支持用空桶抽取冷凝水。
- **燃料映射与废物输出安全性** — 固体物品能力和右键路径只接受 Fuel Reactant；废物输出遵守目标物品最大堆叠数，并按完整 source/product 批次守恒换算；非法 mapping 与负 Tank 索引安全拒绝。
- **物品能力吞吐** — 燃料输入能力不再每次调用只接收一批，而是根据物品数量和反应堆剩余容量尽可能接收完整批次。
- **流体单位换算** — Reactant 流体 Tank 的容量、显示和填充量按 mapping source/product 比例统一换算；容量优先采用当前燃料对应的流体 mapping，并对异常映射和超大换算结果安全处理。
- **流体有效性契约** — 冷却剂、蒸汽和 Reactant 燃料 Tank 的 `isFluidValid` 只报告永久允许的流体类型，不再因当前槽内容、燃料类型或剩余容量变化而让管道缓存错误过滤规则。
- **能力生命周期** — 初始化失败或方块移除时显式释放反应堆组合流体 handler，外部缓存的旧能力引用不再能向脱离世界的控制器写入燃料。
- **初始化期间的燃料交互** — 玩家在控制器首个服务端 tick 初始化完成前手持燃料右键时，不再误开 GUI；物品保持不变，并明确提示控制器仍在初始化或初始化失败。
- **流体输入防御** — 冷却液和蒸汽输入的 `fill()` 现在主动执行与 `isFluidValid()` 相同的 coolant/vapor 映射过滤，不依赖调用方预检或 ER 内部容器实现细节。
- **批量燃料反馈与扣除** — 手持燃料右键改为一次模拟、一次执行并按实际成功插入的完整批次数扣物品；达到容量上限时只显示一条“已注入且已满”反馈，不再被普通成功提示覆盖。
- **控制器初始化就绪状态** — 菜单新增独立的控制器就绪同步位；反应堆按钮首帧默认禁用，反应堆/涡轮界面在初始化期间显示明确提示，控制棒、开关和废物清除 C2S 操作也会由服务端拒绝并反馈，不再显示伪零状态或记录伪成功。
- **超大燃料映射完整批次** — 固体和液态燃料输入在接近 `int` 上限时改为截断到最大可表示的完整 product 批次，不再整次拒绝或向底层提交半批 Reactant；Simulate/Execute 返回值均按实际完整批次数换算来源资源。
- **存档数据保全** — 移除控制器 NBT 上无效的 `toString()` 大小估算，避免额外大字符串分配及超限数据被默认控制器状态静默覆盖。
- **涡轮布局稳定性** — 偶数横向尺寸的虚拟转子中心不再受世界坐标正负影响。
- **文档同步** — README 改为说明实际的能力、管道和手持容器输入方式，不再描述不存在的 GUI 燃料槽。

### Changed

- Mod version bumped to `1.0.0-beta17`.


### Fixed（2026-09-09 全局压测后追加）

- **涡轮机涓流工况冷凝蒸汽丢失（双端）** — ER2 上游 `FluidContainer.onCondensation()` 先 extract 后映射，栈适配器对空栈短路跳过注水 lambda，返回值被 `TurbineLogic` pop 丢弃；低流量每 tick 触发时冷凝水近乎全损。控制器在 `tick()` 前后夹读流体罐，命中特征（消耗>0、蒸汽归零、水量未变、非 VentAll、存在映射）时调用公开的 `condensate(consumed, mapping)` 重放补齐。运行时验证：10000 蒸汽灌入 → 10000 水全额冷凝，涓流/满速/停机/8 机阵列/中途重启场景均守恒。
- **手持燃料右键注入的 64 批量上限（双端）** — 右键路径硬编码 `Math.min(count/source, 64)`，大堆叠模组燃料需多次右键；移除该上限，与物品能力管道路径的无界批量语义一致（溢出防护由既有 `MAX_VALUE/productAmount` 钳制承担）。
- **客户端控制棒限流的世界过渡绕过（双端）** — GUI 在 `level` 为 null 的加载/重连过渡期发送控制棒包时，去抖检查被跳过且状态停留哨兵值，单击可连发多条 C2S 包；改为 fail-closed：世界刻不可用时直接不发送。
- **Jade 提示混淆"初始化中"与"初始化失败"（双端）** — 服务端对两种状态都发 `Initialized=false`，客户端一律显示红色"初始化失败"，误导玩家以为加载中的机器损坏；现区分三态：失败红字、排队初始化黄字（复用既有 `controller_initializing` lang 键）。
- **`initController()` 冗余双重同步（双端）** — 方法级 `synchronized` 内再嵌套同监视器 `synchronized(this)` 块，可重入无额外保护；移除内层锁块，保留二次守卫 if 并注明其防御目标（重入路径穿透半初始化状态）。

### Added（2026-09-09 全局压测后追加）

- dev 专用 `/cerdev` 扩展（仅开发环境，不进入生产路径）：`rods <pos> [adj|set]`（与 GUI 控制棒包相同的服务端调整路径）、`waste <pos> [inject|count]`（废物槽 extractItem 与测试态注入）、`dump` 新增涡轮转子/发电/储能与反应堆燃料/废物读数。
- 仓库根新增 `soak-test.sh`：6 阶段可重复浸泡回归（区块卸载重载、破坏重建与容量钳制、toggle 锤击、灌排守恒、汽化窗口、TPS），当前 PASS=32 FAIL=0。


### Fixed

- **反应堆水→蒸汽路径贯通** — 之前反应堆只输出 FE，水无法进入 ER 内部 `FluidContainer` 也不会产出蒸汽。新增 `BypassFluidHandler`（`common/capability/BypassFluidHandler.java`）直接代理到 `FluidContainer` 的 `insertLiquid` / `extract` API，绕开 `IFluidContainerAccess._accessGovernor`（无 FluidPort 部件时拒绝 fill/drain）和 `MultiblockReactor.getFluidHandler` 的 `isActive()` 前置检查；`CompactReactorController` 覆写 `getFluidHandler(IoDirection)` 始终返回此旁路 handler。现在流体管道灌入水后，反应堆热量循环可正常产生并输出蒸汽。两条 README 已知限制中的首条移除。

### Fixed（2026-08-28 运行时验证后追加）

- **反应堆 variant 切换 Basic→Reinforced（致命，双端）** — 1.21.1 dev server + RCON 运行时实测确认：ER2 的 `ReactorVariant.Basic` 从未设置流体参数（`partFluidCapacity=0`、`maxFluidCapacity=0`、`vaporGenerationEfficiency=0`），是纯被动堆。压缩反应堆若使用 Basic，`resizeFluidContainer()` 算出流体容量恒为 0 → ① `fill()` 注水被拒（实测 20000 mB 全部返回 0）；② `vaporize()` 因 `getFreeSpace(Gas)=0` 每 tick 直接返回，既不汽化也不吸热，堆温无界上涨（实测复现旧报告中"堆温 1567+ 而水量纹丝不动"的现象）。切换 `ReactorVariant.Reinforced`（1000 mB/外壳块、上限 200,000 mB、汽化效率 0.85）后全链路实测通过：水 20000 mB 注入成功、堆温过沸点后蒸汽产出（水 20000→13073 mB / 蒸汽累计 4356 mB）、`drain` 经输出路径抽出 `bigreactors:steam` 正常。1.20.1（ER2 2.0.84）反编译确认 Basic 同样未设流体参数，同款修复。副作用：能量缓冲容量与被动等效 FE 输出按 Reinforced 参数重新计算（约 3 倍于 Basic），README 已注明。
- 新增 dev 专用诊断指令 `/cerdev`（仅 `!FMLEnvironment.production` 注册，op 权限 2，1.21.1）：`fill`/`drain`/`fuel`/`active`/`dump`，其中 fill/drain 走与真实管道完全相同的 `level.getCapability(FluidHandler.BLOCK)` 路径，dump 反射读取 ER 内部 FluidContainer 容量/罐内容/冷却剂注册表解析链，用于无管道 mod 的 dev 环境验证水→蒸汽链路。
- **涡轮机蒸汽→FE→水闭环** — 之前涡轮机能存蒸汽但不发电：`setInductorEngaged(true)` + `setMaxInsert(MAX_VALUE)` 已就位，但外部蒸汽进不到 `FluidContainer`，转子不转、零发电。新增涡轮侧的 `getFluidHandler` 覆写（与反应堆同款 BypassFluidHandler）+ 新增 `getEnergyGeneratedLastTick()` override 把 ER 的 `MultiblockTurbine.getEnergyGeneratedLastTick()` 接到 ICompactController 接口（之前 GUI Power 字段因默认返回 0 而恒为 0）。现在管道灌入蒸汽后转子加速旋转、`generateEnergy()` 写入 ER 能量缓冲、通过 `IEnergyStorage` 输出到能量线缆，冷凝水回流到管道。两条 README 已知限制中的第二条移除。

### Fixed（2026-08-28 第七轮全面体检，6 个并行区域代理审计后修复）

- **1.21.1 配方完全失效回归（P0）** — 第六轮体检 A3 把 1.21.1 的 `compact_reactor`/`compact_turbine` 配方 `result` 键从 `"id"` "统一"成了 `"item"`，而 1.20.5+ 配方格式要求 `"id"`（ER2 1.21.1 官方 jar 实证），压缩机/涡轮机在 1.21.1 上无法合成。已改回 `"id"`（1.20.1 保持 `"item"`，双端格式本就不通用）。
- **ER2/ZeroCore 依赖版本范围上界失效（双端）** — `[1.21.1-2.4.9,2.5.0)` 这类区间被 ComparableVersion 解析为 `(1,21,1,...)` vs `(2,5,0)`，首位 1<2 恒小于上界，上界形同虚设。全部改为带 MC 前缀的 `[1.21.1-2.4.9,1.21.1-2.5.0)` / `[1.20.1-2.0.84,1.20.1-2.1.0)` 等。
- **1.20.1 PENDING_INIT 读档死锁风险回移植** — 1.21.1 的"待初始化队列 + ServerTickEvent 排空"模式回移植 Forge 1.20.1：`onLoad()` 不再用 `getServer().execute()`（`BlockableEventLoop.execute` 在主线程调用时会内联执行，防不住 chunk 加载栈内的同步初始化死锁），统一入队到 tick 顶层处理。
- **markInitFailedAndUnregister 能力失效 + 幽灵控制器守卫（双端）** — serverTick 异常隔离后未失效对外能力：1.20.1 现调 `invalidateCaps()`、1.21.1 调 `invalidateCapabilities()`，并新增 `onControllerReleased()` 钩子清空物品处理器；`getController()` 加 `isRemoved()` 守卫，防止方块移除后残留管道查询懒加载出全新空控制器吞物品。
- **1.20.1 物品能力 LazyOptional 缓存** — 主类每次能力查询都 `LazyOptional.of(...)` 新建实例且从不失效，违反 Forge 规范（同能力须同实例 + 失效通知）；改为 TileEntity 缓存单实例并在 `invalidateCaps` 中失效。
- **废物预览与提取映射统一（双端）** — `getStackInSlot` 预览用第一个 mapping、`extractItem` 用贪心选择，管道探测结果可能与实际取出物不一致；抽出共用的 `findWasteMapping()`（贪心最大 source≤废料量）供两者调用。
- **控制棒客户端去抖（双端）** — 服务端 B3 限速（1 包/tick）静默丢包后，客户端本地预测值与服务端永久分叉（本地值首次同步后不再被覆写）；客户端同 tick 内的后续点击现在直接忽略，与服务端限速对齐。
- **Jade 能量行双单位（双端）** — `formatEnergy()` 已带 FE/kFE 单位，复用的 GUI lang 键又拼一个 "FE"，Jade 显示 "…FE FE"；新增无单位键 `jade.compactextremereactor.energy`（en/zh），Jade Provider 改用它。
- **涡轮 `hasPendingControllerTag` 反射移除（双端）** — 反射读基类 private 字段改为基类 `protected final` 访问器，ZeroCore/混淆环境更稳。
- **流体脏标记回调接线（双端）** — `BypassFluidHandler` 的 dirtyCallback 构造参数从未被接线；`ICompactController` 新增 `setFluidDirtyCallback`，基类 initController 统一注册 `this::setChanged`，管道 fill/drain 后立即落盘（原来依赖 serverTick 1 秒兜底）。
- **杂项** — 未知机器动作 default 分支补 debug 日志（双端）；1.20.1 `registerMessage` 显式 `PLAY_TO_SERVER` 方向；1.20.1 非法燃料 mapping 分支由 PASS（继续物品放置流程，行为不可控）改为提示后打开 GUI；1.21.1 客户端燃料预测补 `productAmount>0` 校验与服务端对齐；`voidWaste` 乘法 long 防溢出；菜单 `stillValid` 加 `isRemoved()` 守卫；1.21.1 涡轮 `turbine.sizeX/Y/Z` 上限 64→32 与 1.20.1 对齐；修正 4 处过时注释（`SimulatedIrradiationSource` 头注释"六方向"与实现不符、反应堆控制器 `recalculateCoords` javadoc 提及不存在的 buildBoundingBox 调用、1.21.1 涡轮转速"红色警告"实为琥珀色提示、1.21.1 ModPackets 限速注释语病）；`.gitignore` 补 server_run.log/thread_dump*/rcon-port.ps1/net/it/GUI 原型页；`build.gradle` mod_version 兜底 beta4→beta16。
- **maven-publish 完全不可用（第七轮体检遗留项，本轮修复）** — `url "file://${project.projectDir}/repo"` 在 Windows 带空格路径下无法转 URI（实测 `publish` 直接失败 `Cannot convert URI ... to a file`）。重写发布配置：显式坐标（groupId=`com.compact.extremereactor`、artifactId=`<mod_id>-<Loader>-<MC>` 保证双端唯一、version=`mod_version`）；仓库改 `rootProject.file('build/maven-repo')`（经 `project.uri(File)` 正确转义，随 `build/` 进 .gitignore）；不接 `from components.java` 而直接 `artifact(reobfJar ?: jar)`——顺带发现第二个问题：1.20.1 的 `jar` 任务产物是 devlibs 下的**未重映射中间 jar**，最终可分发 jar 由 `reobfJar`（RemapJar）写入 libs，直接发布 jar 会发布错误产物。双端 `publish` 实测通过，发布 jar 与 libs 产物 MD5 逐字节一致，pom 无 ER/ZeroCore 依赖泄漏。

### Fixed（2026-08-29 运行时验证后追加）

- **涡轮机进蒸汽走错槽（致命，双端）** — `BypassFluidHandler` 的 fill 写死走 `insertLiquid`（反应堆进水语义），而蒸汽对涡轮机是 **Gas 槽**进料，导致涡轮机流体管道灌蒸汽恒为 0（1.21.1 dev server + RCON 实测复现：fill 10000 mB 返回 0）——beta16 CHANGELOG 声称的"涡轮蒸汽→FE→水闭环"此前实际不可用。修复：`BypassFluidHandler` 新增 `fillTarget`/`drainPreference` 槽位路由参数（ER 无公开 `insertGas`，用父类 `IndexedStackContainer.insert(Index,...)` 直插 Gas 槽）；`CompactTurbineController` 进料指定 `FluidType.Gas`、输出指定优先 `FluidType.Liquid`（避免滞留蒸汽被从水管抽走）。
- **双端运行时闭环实测（RCON 冒烟）**：
  - 1.21.1 dev server：反应堆水→蒸汽回归通过（水 20000 mB → 蒸汽 16984 mB、汽化 570/tick、FE 7322/tick）；蒸汽 5000 mB 抽出正常；涡轮机蒸汽 10000 mB 全部接收 → 消耗殆尽 → 能量缓冲 25032 FE → 冷凝水 9000 mB → drain 抽出 `minecraft:water` 2000 mB；跨重启存档精确恢复（蒸汽 11984 = 16984−5000）；日志 0 异常。
  - 1.20.1 生产服务器（prodtest，Forge 1.20.1-47.4.10）：PENDING_INIT 回移植后启动/读档无死锁（Done 1.846s）；新放置机器正常初始化（完整 controller NBT、容量 21.87M FE）；燃料注入后燃烧链路正常（燃料 238 消耗、cyanite 废料累积、FE 涨至 7.15M）；涡轮机蒸汽 10000 mB → FE 20702 + 冷凝水 9000 mB；日志 0 异常。**1.20.1 端首次完成全链路运行时验证**。
  - 说明：1.20.1 的 dev `runServer`/`runClient` 无法加载 SRG 映射的 ER2/ZeroCore 生产 jar（MojMap dev 运行时 `NoSuchMethodError`），运行时验证必须走 prodtest 生产服务器（见 [[build-and-dev]]）。

### Added

- **BypassFluidHandler**（双端 `common/capability/BypassFluidHandler.java`）— 单方块机器专用的 `IFluidHandler` 旁路实现：包 `FluidContainer` + `isInput` 方向位，`fill` 走 `insertLiquid`、drain 走 `extract(FluidType.Gas/Liquid, ...)`，立即生效、不需要 serverTick 批处理也不与 ReactorLogic/TurbineLogic 内部流处理冲突。

### Notes

- 仍为 1.0.0-beta 预发布版本，双端（Forge 1.20.1 + NeoForge 1.21.1）同步构建。

## [1.0.0-beta8] - 2026-08-20

### Fixed

- **GUI 菜单在客户端仍可操作** — `stillValid()` 在客户端侧返回 false，导致玩家打开 GUI 后方块被判定为"不可用"；现在两端一致放行。
- **燃料重复注入漏洞（高）** — 燃料槽点击时 `Simulate` 阶段先做数量校验、`Execute` 阶段再减少放置槽数量，但两阶段间物品堆可被替换，导致同一份燃料被重复塞入燃料容器；现改为以模拟结果为准、执行阶段不再二次注入。
- **能量条渲染 int 溢出（中）** — 能量条按当前/最大能量计算渲染宽度时使用 `int` 相乘可能溢出（能量上限数百万 FE），渲染比例错乱；改用 `long` 计算。
- **菜单数据 int 截断溢出** — 反应堆/涡轮机菜单的 4 个数据源（能量、燃料、废物、温度）在打包进菜单槽时用 `int` 存放大数值能量被截断，GUI 显示错误；改用大容量传输。
- **涡轮机 `getRotorComponentTypeAt()` Y 轴墙体误判** — Y 轴检测逻辑把实体转子列当成墙体排除，导致转子组件类型判断失败。
- **`getReactorHeat()` NoSuchElementException** — 反应堆控制器取第一加热组件时用 `getFirst()`，空列表抛异常导致崩溃；改用安全取值。
- **废物条使用燃料容量代替废物容量** — 废物条渲染比例基于燃料容量计算，显示错误；改用废物容器容量。
- **涡轮机线圈 tag 解析错误** — 线圈 tag 解析用了错误的注册名/路径，导致线圈数量恒为 0。
- **`onBlockRemoved()` 未清理 `_pendingControllerTag`（低）** — 方块移除时待应用的 controller NBT 未清空，可能把残留下发状态应用到后续方块。
- **`adjustControlRod` 快速点击竞态（低）** — 快速点击控制棒按钮时多次请求叠加，服务端处理顺序错乱；现在模拟阶段先行确认。
- **控制棒英文文本超出 GUI 边界（低）** — 控制棒插入比例文本过长被截断；调整布局。
- **反应堆屏幕添加废物量标签（低）** — 废物容量没有单独标签，补上。

### Changed

- **构建网络优化（国内环境）** — `build.gradle` 增加 Aliyun Maven Central 镜像（20MB/s），并移除连接被重置的 `maven.neoforged.net` 官方仓库（改走 `neoforged.forgecdn.net` 镜像），`createMinecraftArtifacts` 不再卡死（原来每个依赖重试 8 次、一次构建十几分钟以上）。

## [1.0.0-beta7] - 2026-08-18

### Changed

- Rebuilt and re-verified after the beta6 GUI-toggle / new-machine initialization fixes. Both Forge 1.20.1 and NeoForge 1.21.1 clean-world dedicated-server smoke tests pass: fresh `setblock`-placed machines now initialize their controllers immediately (previously they stayed inert as `{id}`-only entries), capacities report real values (reactor 7.29M FE, turbine 8.91M FE), and no exceptions appear while ticking.

## [1.0.0-beta6] - 2026-08-17

### Fixed

- **GUI toggle button (开关) had no response on newly placed machines** — `ReactorData.get()` / `TurbineData.get()` returned 0 for all data slots including `DATA_POS_READY` when the controller was null, which kept the toggle/control-rod buttons disabled in the GUI. Position data is now resolved before the controller null check, so buttons are always enabled.
- **Newly placed machines (setblock / player placement) did not initialize their controller** — the controller initialization was deferred to the first `serverTick()`, but the ticker registration path was unreliable for `setblock`-placed block entities. Both `setLevel()` and `onLoad()` now call `initController()` proactively, and `setChanged()` ensures the initial state (active, capacities, control rod ratio) is persisted immediately.
- All Chinese "压缩反应堆" references renamed to "压缩极限反应堆" (block lang, README_zh_CN, Javadocs) for both 1.20.1 and 1.21.1.

### Changed

- `AbstractCompactMachineTileEntity` now overrides `setLevel()` to initialize the controller when the block entity is first attached to a world, ensuring setblock and player-placed machines work identically.
- `DATA_POS_READY` and block position data are now served independently of the controller state in both `ReactorData` and `TurbineData`, so the GUI is always functional even if the controller hasn't been created yet.

## [1.0.0-beta5] - 2026-08-16

### Fixed

- **Crash on world tick with ER2 2.4.21+** (`IndexOutOfBoundsException: Index: 0, Size: 0`, crash-2026-08-16_22.51.44): ER2's `onMachineAssembled()` → `createFuelRodsLayout()` → `getControlRodByIndex(0)` indexes the (empty) attached-control-rod list after bounds-checking against the *simulated* `getControlRodsCount()`. The compact controller now overrides `getControlRodByIndex` to return `Optional.empty()` — matching the "part does not exist" contract; the layout builder then falls back to `Direction.UP` safely. This crashed instantly on placement with ER2 2.4.28, and after ~10 s via the delayed layout task on older versions.
- **Energy buffer capacity was always 0** (both versions): ER's `onMachineAssembled()` sizes the buffer as `per-part capacity × getPartsCount()` (no-arg), which returned 0 for the part-less compact controllers. Both compact controllers now override the no-arg `getPartsCount()` with the simulated structure block count — the GUI energy bar and `getMaxEnergyStored()` report real values again.
- **Reactor generated no energy: fuel heat never reached the reactor body** — ER2's fuel→reactor heat transfer coefficient is the sum of *real* fuel rod conductivities (0 without parts), so fuel burned but reactor heat (and thus power) stayed at zero. The compact controller now simulates the real-world approximation (4 exposed faces × air conductivity × simulated rod count). Verified live: reactor heat climbs, FE accumulates to the full 7.29M buffer.
- **FE compensation was silently rejected** — ER2 generator buffers ship with `maxInsert=0` and old saves restore that value; the buffer now has insertion force-reopened after every NBT restore (`syncDataFrom`) so the passive-equivalent FE credit actually lands.
- **Forge 1.20.1 jar was rejected at load** — the mods.toml used the NeoForge-style `type="required"` dependency field; Forge 1.20.1 requires `mandatory=true`. All dependencies in the 1.20.1 template were converted.
- **NeoForge 1.21.1 dev environment failed to load the mod** — metadata file renamed from legacy `META-INF/mods.toml` to `META-INF/neoforge.mods.toml` (both names work in production, only the canonical one passes the dev classpath scanner).
- **Fuel bar capacity wrong on Forge 1.20.1**: `getFuelCapacity()` returned the *energy* buffer capacity instead of the fuel container capacity (`MultiblockReactor.getCapacity()` no-arg); now matches the 1.21.1 implementation.

### Known limitations

- **Reactor water→steam conversion and the compact turbine do not produce steam power yet.** Steam can be stored in the fluid tanks (verified), but ER2's vaporization/rotor-spin paths depend on deeper part-based internals that the single-block simulation does not currently provide (inductor engagement, rotor flow conditions). The compact reactor outputs FE (verified at full 7.29M buffer / ~3.7k FE/t with default config). Steam support is on the roadmap.
- Forge 1.20.1 `runServer`/`runClient` dev runs fail because the local ZeroCore/ER2 jars are production (SRG-mapped) builds; production installs are unaffected.

### Changed

- Forge 1.20.1 capability providers now cache their `LazyOptional`s and invalidate them in `invalidateCaps()` instead of creating a new wrapper on every query.
- The GUI fuel slot only accepts items mappable to an ER reactant (`ReactantMappingsRegistry`), and fuel consumption no longer can shrink a stack below zero.
- README / README_zh_CN: config key names and defaults corrected to match the code (`reactor.fuelRods=16`, sizes 9×9×9 / 9×11×9, coil radius 3); FE output documented; steam cycle marked as not yet available.

### Verified (live 1.21.1 dedicated-server simulation)

- Block placement and 5+ minutes of fueled ticking with zero exceptions (the exact path that crashed with ER2 2.4.28).
- Energy buffer capacity 7,290,000 FE (reactor) / 8,910,000 FE (turbine) — previously always 0.
- Fuel burn, heat transfer (fuel 4300° → reactor 570°→2400°), FE compensation filling the buffer to capacity, control-rod ratio persistence.

## [1.0.0-beta4] - 2026-08-16

### Added

- **Forge 1.20.1 support**: the mod now builds and runs on both Forge 1.20.1 (47.1.106) and NeoForge 1.21.1 via a Stonecutter multi-version setup.
- **Multi-version project structure**: sources moved to `versions/1.20.1` and `versions/1.21.1`, with per-version `gradle.properties` for loader / dependency versions; `stonecutter.gradle.kts` switches the active version.

### Fixed

- 1.20.1 API differences against the NeoForged Forge 47.1.x branch: `Capabilities` → `ForgeCapabilities`, `loadAdditional()` → `load()`, `registerConfig()` → `addConfig()`, `LazyOptional.ofNullable()` → `of()` with null check, removed `Level.getCapability(cap, pos, dir)`, removed `RegisterMenuScreensEvent` (now `MenuScreens.register` in `FMLClientSetupEvent`), `MenuType` two-arg constructor, `Registries.CREATIVE_MODE_TAB`, `Entity.level()` accessor.

## [1.0.0-beta3] - 2026-08-12

### Added

- **Compact Reactor** block: compresses a full ER reactor (fuel rods, control rods, power taps, internal size — all configurable) into a single block.
- **Compact Turbine** block: compresses a full ER turbine (rotor with blades, gold coil, steam/condensate cycle) into a single block.
- Full GUI: live bars for energy / fuel / waste / steam / generated power, control rod insertion adjustment (−5/+5), machine on/off toggle, void-waste button.
- Auto fuel injection: a fuel item (e.g. yellorium ingot) placed in the GUI fuel slot is mapped to a `Reactant` and inserted into the fuel container.
- Capabilities: `IEnergyStorage` (power pushed to all 6 adjacent faces every tick, simulated `ActivePowerTapFE`) and `IFluidHandler` (reactor: water in / steam out; turbine: steam in / water out).
- NBT save format delegated to the ER controller (`syncDataFrom` / `syncDataTo`), same format as ER.

### Fixed

- Control rod insertion ratio is now persisted to NBT (previously reset to 0 after world reload).
- Empty NBT no longer overwrites existing saves when the controller is not yet initialized (previously a fresh `controller` tag could erase saved data).
- Toggling the machine on/off now marks the block entity dirty so the state survives chunk unload.
- `mods.toml` `loaderVersion` corrected to NeoForge `[2,)` so the mod is actually loaded by the NeoForge 2.x mod discoverer.

## [1.0.7-beta2] - 2026-08-12

### Fixed

- Persist control rod insertion ratio; prevent empty NBT overwrite of saves; mark block dirty on active toggle.

## [1.0.7-beta1] - 2026-08-11

### Added

- Initial single-block simulation architecture based on Extreme Reactors 2 / ZeroCore 2 (NeoForge 1.21.1).
