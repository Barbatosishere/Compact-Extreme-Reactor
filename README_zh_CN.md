# Compact Extreme Reactor（压缩极限反应堆）

> [English](README.md) | **中文**

将 [Extreme Reactors](https://github.com/ZeroNoRyouki/ExtremeReactors2) 的大型多方块机器（反应堆 / 涡轮机 / 流化器）**压缩为单个紧凑方块**的模组，支持 **Forge 1.20.1** 与 **NeoForge 1.21.1** 双版本（基于 [Stonecutter](https://stonecutter.kikugie.dev/) 多版本构建）。

放置压缩方块并在结构页装入组件即可运行，无需搭建真实的多方块结构。三种机器由组件数量共同限制有效规模；反应堆和涡轮机复用 ER 模拟控制器，流化器复用 ER 配方和 ZeroCore 配方处理机制。

结构页支持外壳、燃料棒/控制棒、转子/金线圈及流化器注入器；配置提供基准参数、最低数量和增长比例。完整规则与旧存档迁移见 [结构组件与升级](docs/structure-components.md)。旧存档升级后需补入最低组件，已有资源保留。

---

## ✨ 功能特性

- **压缩极限反应堆**（`compact_reactor`）：燃料棒、控制棒、功率接口数量与内部尺寸可通过配置调整；能量每游戏刻向 6 个相邻方向主动推送（模拟 ActivePowerTapFE），并按被动模式等效公式补记 FE（输出随堆温/冷却状态浮动；默认配置沸水平衡态实测约 0.7k FE/t，缺水干烧时更高）。流体接口已贯通水→蒸汽闭环：灌入的水被反应堆热量蒸发，蒸汽由同一 `IFluidHandler` 输出（同步流体路径，无需 FluidPort 部件）。
- **压缩涡轮机**（`compact_turbine`）：转子/线圈规模由模拟布局与配置决定；蒸汽经流体接口灌入后转子加速旋转并发电，冷却后的水由同一 `IFluidHandler` 排出——完整的蒸汽→FE→水闭环。
- **压缩流化器**（`compact_fluidizer`）：消耗 FE 加工 ER 的单固体、固体混合和流体混合配方；提供两个物品输入槽、两个各 8,000 mB 的流体输入罐，以及按配置内部体积计算容量的产物罐。支持管道或手持物品、流体容器右键进料；GUI 显示模式、进度、原料和产物，并提供开关与清空进料按钮。
- **完整 GUI**：控制棒插入比例调节（-5/+5）、机器开关、清除核废料按钮；能量/燃料/废物/蒸汽/发电量实时显示；
- **燃料注入**：固体燃料可通过方块物品能力（槽位 0）输入，也可手持燃料右键反应堆；黄铀液、钚液、蓝钚液等流体燃料以及冷却液使用方块流体能力或容器输入；
- **状态持久化**：反应堆和涡轮机沿用 ER 控制器 NBT；流化器通过 ZeroCore 保存原料、产物、能量和配方进度。读档时按当前配置重新计算容量。

## ⚙️ 技术原理

反应堆和涡轮机的单方块模拟架构：

```
单个方块 TileEntity
   └── 持有并驱动 ER 多方块控制器（MultiblockReactor / MultiblockTurbine 子类）
         ├── isEmpty()/isAssembled() 固定为 false/true → 绕过真实部件装配检查
         ├── getReferenceCoord() → 指向压缩方块自身 → ZeroCore 网络同步正常工作
         ├── getPartsCount()/getBoundingBox()/getReactorVolume() → 返回配置的模拟值
         └── simulateAssembly() → 调用受保护的 onMachineAssembled() → 初始化容量
```

- 每个游戏刻调用 `updateMultiblockEntity()` 驱动完整的 ReactorLogic / TurbineLogic 模拟（辐射、燃料消耗、热量、发电、流体循环）；
- 流化器由自有压缩控制器通过 ZeroCore 配方持有器处理 ER 配方，不实例化 ER 的多方块流化器控制器；
- 能量通过 `IEnergyStorage` 能力暴露：反应堆和涡轮机每 tick 主动向 6 个相邻方块推送 FE，流化器只接受 FE 输入；
- 流体通过 `IFluidHandler` 能力暴露：反应堆输入冷却液和燃料、输出蒸汽和废液；涡轮机蒸汽进/水出；流化器原料进/产物出；
- 控制指令（控制棒调节、开关、清除废料、清空流化器进料）通过对应平台的网络系统发送到服务端（1.21.1 用 NeoForge payload，1.20.1 用 Forge `SimpleChannel`）。

## 📦 前置依赖

### Forge 1.20.1

| 依赖 | 版本 | 说明 |
|---|---|---|
| Minecraft | 1.20.1 | 必需 |
| Forge | 47.1.106 | 必需（开发版本） |
| Extreme Reactors | 1.20.1-2.0.84 | 必需（运行时前置） |
| ZeroCore2 | 1.20.1-2.1.45 | 必需（多块控制器 API） |

### NeoForge 1.21.1

| 依赖 | 版本 | 说明 |
|---|---|---|
| Minecraft | 1.21.1 | 必需 |
| NeoForge | 21.1.x | 必需（开发版本 21.1.207） |
| Extreme Reactors | 1.21.1-2.4.9 | 必需（运行时前置） |
| ZeroCore2 | 1.21.1-2.4.9 | 必需（多块控制器 API） |

> ⚠️ **构建依赖说明**：`libs/` 目录下包含 ER / ZeroCore 的本地 jar（官方 Maven `maven.zerono.it` 网络不可达，从 Modrinth 下载）。**请勿删除或忽略该目录**，否则仓库克隆后无法编译。

## 🔨 构建

项目使用 Stonecutter 在两个版本间共享同一份构建脚本。当前激活版本由 `stonecutter.gradle.kts` 决定；各版本的 loader / 依赖版本配置在 `versions/<mc>/gradle.properties`。

```powershell
# JDK 17（1.20.1）/ JDK 21（1.21.1）——工具链自动选择
# 显式构建指定版本：
.\gradlew.bat :1.20.1:build
.\gradlew.bat :1.21.1:build
# 或构建当前激活版本：
.\gradlew.bat build
```

构建产物位于 `versions/<mc>/build/libs/compactextremereactor-<版本>-<Loader>-<MC版本>.jar`（如 `compactextremereactor-1.0.0-beta4-Forge-1.20.1.jar` / `compactextremereactor-1.0.0-beta4-NeoForge-1.21.1.jar`），按对应 loader 放入 `mods/` 目录即可。

> 💡 如果网络环境不佳导致依赖下载失败，`gradle.properties` 已配置 IPv4 优先（`-Djava.net.preferIPv4Stack=true`），且 `build.gradle` 内置了可达镜像（`neoforged.forgecdn.net`、BMCLAPI）作为兜底。

开发启动使用 `.\gradlew.bat :1.20.1:runServer` 或 `:1.21.1:runServer`，客户端将任务名改为 `runClient`。LegacyForge 会将本地 1.20.1 生产依赖自动重映射为开发环境的 Mojang 名称，无需手工转换 JAR。ER 和 ZeroCore 从运行时 classpath 加载；启动任务只清理 `run/mods` 中这两个依赖已知的旧副本，避免重复加载。

测试独立世界时可追加 `-Pcer_run_directory=build/test-run`，运行目录将改为 `versions/<mc>/build/test-run`。

GitHub Actions 构建两套版本，并用 `tools/verify_artifact.py` 检查 JAR 元数据、Java 版本、机器类、配方、掉落表和资源。该检查验证打包结果；游戏内回归使用下文的开发指令。

## 🎮 使用说明

1. 放置压缩极限反应堆 / 压缩涡轮机 / 压缩流化器方块（创造模式标签页或合成）；
2. 右键方块打开 GUI：
   - 点击右侧结构按钮，装入最低组件；金色行表示当前规模瓶颈；
   - **反应堆**：GUI 用于状态和控制；固体燃料可经物品能力或手持右键输入，流体燃料和冷却液可用流体管道或容器输入；用 `-5/+5` 调节控制棒；开关按钮启停机器；清除废料按钮排出核废料；
   - **涡轮机**：GUI 为状态显示；用流体管道或容器灌入蒸汽，并从流体输出端取出冷凝水；
   - **流化器**：输入 FE，通过物品/流体管道或手持原料、流体容器右键进料，再经管道或容器抽取产物流体。GUI 显示配方进度，并提供启停和清空进料按钮；清空进料会丢弃现有原料；
3. 用能量管道 / 导线从反应堆、涡轮机输出电能，或向流化器输入电能（单方块各方向等价）。

## ⚠️ 已知限制

- 压缩反应堆使用向上的单条辐射路径，避免模拟热量被六个方向重复放大；它不复现真实燃料棒布局及其控制棒方向性热响应。
- 反应堆和涡轮机模拟依赖 ZeroCore 的内部边界框字段。兼容性在机器装配时检查；API 不兼容时由初始化错误处理器禁用该机器并记录原因。

流体能力合并展示输入和输出罐。槽位编号用于查询内容与有效性，`IFluidHandler.fill/drain` 方法没有槽位参数：填充路由到输入，抽取路由到输出。反应堆遇到同时注册为燃料和冷却剂的流体时按燃料处理；自动化需要区分用途时应避免重叠映射。不指定流体类型的反应堆抽取只输出蒸汽，废液需要指定流体类型后抽取。

涡轮机 FE 缓冲满仓时同步暂停进汽、冷凝和发电，抽出能量后自动恢复；暂停期间没有消耗蒸汽，因此没有冷凝水需要补偿。流化器在两个输入罐均有流体时优先选择流体混合，其次为固体混合或单料加工；自动化同时使用两种进料时建议分别使用机器。

## ⚙️ 配置（`config/compactextremereactor-common.toml`）

下表参数提供基准规模，实际规模由结构组件决定。`<机器>.structure.<组件名>.minimum` 设置最低数量，`growthPercent` 设置每件额外组件支持的增长比例。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `reactor.fuelRods` | 16 | 模拟燃料棒数量（燃料容量 = 数量 × 每棒容量） |
| `reactor.controlRods` | 4 | 模拟控制棒数量 |
| `reactor.powerTaps` | 4 | 模拟功率接口数量 |
| `reactor.sizeX/Y/Z` | 9 | 模拟反应堆尺寸（3–32，能量/流体缓冲随体积缩放） |
| `turbine.coilRadius` | 3 | 涡轮机线圈半径（1–16） |
| `turbine.sizeX/Z` | 9 | 模拟涡轮机尺寸 |
| `turbine.sizeY` | 11 | 涡轮机转轴高度（叶片数 = 层数 × 4） |
| `fluidizer.sizeX/Y/Z` | 9 | 模拟流化器尺寸（3–32；输出罐容量 = 内部体积 × 4,000 mB） |

配置重载后，已有机器会保存状态并按新基准和组件数量重建。流化器缩容保留全部已有流体，排出至可容纳一次完整配方产物后恢复。

反应堆和涡轮机缩容保留全部已有 FE；缓冲已满时暂停发电，已有能量仍可正常抽取。

流化器的 `fluidizer.energyCapacity` 和 `fluidizer.energyTransfer` 分别提供 50,000 FE 和 1,000 FE 的默认基准，实际容量与输入限额随有效规模增长。已有超额能量保留并供配方消耗；储量低于容量前拒绝继续输入。

存档中的库存槽数不能改变流化器的两个物品槽和两个输入罐。读档保留合法槽中的原料，并忽略槽位编号缺失、非数字、非整数或越界的条目。

## 📁 项目结构

```
build.gradle                    # Stonecutter 共享构建脚本
stonecutter.gradle.kts          # 激活版本切换
libs/                           # 本地 ER / ZeroCore 依赖
tools/
├── rcon-client.ps1            # 浸泡测试使用的 RCON 客户端
├── soak-test.sh               # 基于 RCON 的浸泡回归测试
└── verify_artifact.py         # CI 使用的 JAR 检查（Python 3.11+）
src/main/java/com/compact/extremereactor/  # 双版本共享的 Java 源码
└── common/menu/                # 三种机器菜单及整数拆分同步
versions/<mc>/                 # 各版本源码（1.20.1、1.21.1）
├── gradle.properties          # 该 MC 版本的 loader / 依赖版本
└── src/main/
    ├── java/com/compact/extremereactor/
    │   ├── CompactExtremeReactor.java  # 模组入口
    │   ├── client/             # GUI 与客户端注册
    │   └── common/             # 方块、能力、配置、集成、
    │                           # 模拟控制器、网络、方块实体
    ├── resources/
    │   ├── assets/             # 模型、纹理、语言、方块状态
    │   └── data/               # 战利品表与合成配方
    └── templates/META-INF/    # 模组元数据模板（构建时展开）
```

在 NeoForge 1.21.1 开发服务器中，可对三种机器分别执行 `/cerdev selftest <x> <y> <z>`，检查能力、菜单同步和存档行为。生产环境和 Forge 1.20.1 分支不注册这些指令。

浸泡测试需要 Windows 上的 Git Bash、PowerShell，以及开启 RCON 的 NeoForge 开发服务器和可丢弃的测试世界。准备两台已开启的涡轮机（`2950 100 2950`、`2974 100 2950`）和一台已加燃料且开启的反应堆（`2954 100 2950`），并强制加载 `2950 2950`、`2965 2950` 的区块。测试机器应断开其他能量、流体自动化；脚本会改变机器内容并重建备用涡轮机。在仓库根目录设置连接参数后执行：

```bash
export CER_RCON_HOST=127.0.0.1
export CER_RCON_PORT=25575
export CER_RCON_PASSWORD='<测试服务器的 RCON 密码>'
bash tools/soak-test.sh
```

脚本直接使用已收录的 `tools/rcon-client.ps1`，无需 `rcon-port.ps1`。运行目录和浸泡日志由 Git 忽略。

## 🙏 致谢

- [Extreme Reactors](https://github.com/ZeroNoRyouki/ExtremeReactors2) —— 被压缩的多方块机器本体
- [ZeroCore2](https://github.com/ZeroNoRyouki/ZeroCore2) —— 多块控制器基础框架
- [CompactMekanismMachinesPlus](https://github.com/nanaios/CompactMekanismMachinesPlus) —— 单方块压缩思路参考
- [NeoForge](https://docs.neoforged.net/) —— 模组框架与官方文档

## 📜 许可证

本模组为个人学习 / 社区作品，复用 ER 与 ZeroCore 的 API 与纹理，请遵循其原始开源许可。
