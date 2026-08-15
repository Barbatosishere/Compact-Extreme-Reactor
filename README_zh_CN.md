# Compact Extreme Reactor（压缩极限反应堆）

> [English](README.md) | **中文**

将 [Extreme Reactors](https://github.com/ZeroNoRyouki/ExtremeReactors2) 的大型多方块机器（反应堆 / 涡轮机）**压缩为单个紧凑方块**的模组，支持 **Forge 1.20.1** 与 **NeoForge 1.21.1** 双版本（基于 [Stonecutter](https://stonecutter.kikugie.dev/) 多版本构建）。

放置一个方块，即可获得一整套完整的多方块机器 —— 不需要搭建真实结构，内部完整复用 ER 的反应堆 / 涡轮机模拟逻辑。

---

## ✨ 功能特性

- **压缩反应堆**（`compact_reactor`）：燃料棒、控制棒、功率接口数量与内部尺寸可通过配置调整；水经流体接口输入，蒸汽经流体接口输出，能量每游戏刻向 6 个相邻方向主动推送（模拟 ActivePowerTapFE）；
- **压缩涡轮机**（`compact_turbine`）：蒸汽经流体接口输入，冷凝水输出，转子/线圈规模由模拟布局与配置决定，无蒸汽不转；
- **完整 GUI**：控制棒插入比例调节（-5/+5）、机器开关、清除核废料按钮；能量/燃料/废物/蒸汽/发电量实时显示；
- **燃料自动注入**：把燃料物品（如黄钇矿铤）放入 GUI 燃料槽，自动映射为 Reactant 注入燃料容器；
- **存档兼容**：NBT 直接委托 ER 控制器（`syncDataFrom`/`syncDataTo`），与 ER 存档格式一致，容量由模拟装配重新计算。

## ⚙️ 技术原理

单方块模拟架构（核心思想）：

```
单个方块 TileEntity
   └── 持有并驱动 ER 多方块控制器（MultiblockReactor / MultiblockTurbine 子类）
         ├── isEmpty()/isAssembled() 固定为 false/true → 绕过真实部件装配检查
         ├── getReferenceCoord() → 指向压缩方块自身 → ZeroCore 网络同步正常工作
         ├── getPartsCount()/getBoundingBox()/getReactorVolume() → 返回配置的模拟值
         └── simulateAssembly() → 调用受保护的 onMachineAssembled() → 初始化容量
```

- 每个游戏刻调用 `updateMultiblockEntity()` 驱动完整的 ReactorLogic / TurbineLogic 模拟（辐射、燃料消耗、热量、发电、流体循环）；
- 能量通过 `IEnergyStorage` 能力暴露，并每 tick 主动向 6 个相邻方块推送（模拟 PowerTap）；
- 流体通过 `IFluidHandler` 能力暴露：反应堆 = 水进/蒸汽出，涡轮机 = 蒸汽进/水出；
- 控制指令（控制棒调节、开关、清除废料）通过对应平台的网络系统发送到服务端（1.21.1 用 NeoForge payload，1.20.1 用 Forge `SimpleChannel`）。

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

构建产物位于 `versions/<mc>/build/libs/compactextremereactor-1.0.0-beta4.jar`，按对应 loader 放入 `mods/` 目录即可。

> 💡 如果网络环境不佳导致依赖下载失败，`gradle.properties` 已配置 IPv4 优先（`-Djava.net.preferIPv4Stack=true`），且 `build.gradle` 内置了可达镜像（`neoforged.forgecdn.net`、BMCLAPI）作为兜底。

## 🎮 使用说明

1. 放置压缩反应堆 / 压缩涡轮机方块（创造模式标签页或合成）；
2. 右键方块打开 GUI：
   - **反应堆**：把燃料物品（黄钇矿铤等）放入燃料槽自动注入；用 `-5/+5` 调节控制棒；开关按钮启停机器；清除废料按钮排出核废料；
   - **涡轮机**：纯状态显示，用流体管道灌入蒸汽（如配合反应堆蒸汽输出）；
3. 用能量管道 / 导线从方块任意一面输出电能（单方块各方向等价）。

## ⚙️ 配置（`config/compactextremereactor-common.toml`）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `reactorFuelRods` | 3 | 模拟燃料棒数量 |
| `reactorControlRods` | 3 | 模拟控制棒数量 |
| `reactorPowerTaps` | 6 | 模拟功率接口数量 |
| `reactorSizeX/Y/Z` | 5 | 模拟反应堆外壳尺寸 |
| `turbineCoilRadius` | 2 | 涡轮机线圈半径 |
| `turbineSizeX/Y/Z` | 5 | 模拟涡轮机外壳尺寸 |

## 📁 项目结构

```
versions/<mc>/                 # 各版本源码（1.20.1、1.21.1）
├── gradle.properties          # 该 MC 版本的 loader / 依赖版本
└── src/main/
    ├── java/com/compact/extremereactor/
    │   ├── CompactExtremeReactor.java  # 主类：能力注册、payload 注册、配置
    │   ├── client/
    │   │   ├── ClientHandler.java      # 客户端 GUI 屏幕注册
    │   │   └── screen/                 # 反应堆 / 涡轮机 GUI
    │   ├── common/
    │   │   ├── Content.java            # 方块/物品/方块实体/菜单注册表
    │   │   ├── block/                  # 方块类（GUI 打开、TileEntity 绑定）
    │   │   ├── capability/             # 能量/流体能力包装
    │   │   ├── config/CompactConfig.java # 模拟参数配置
    │   │   ├── menu/                   # 容器（数据槽同步 + 燃料自动注入）
    │   │   ├── multiblock/             # 模拟控制器（核心：ER 控制器子类）
    │   │   ├── network/ModPackets.java # C2S 控制指令数据包
    │   │   └── tile/                   # TileEntity（控制器生命周期/NBT/能力）
    │   └── resources/
    │       ├── assets/                 # 模型/语言（en_us/zh_cn）/方块状态
    │       ├── data/                   # 战利品表/合成配方
    │       └── templates/META-INF/mods.toml # 模组元数据模板（构建时展开）
├── build.gradle               # 共享构建脚本（仓库根目录）
└── stonecutter.gradle.kts     # 激活版本切换
```

## 🙏 致谢

- [Extreme Reactors](https://github.com/ZeroNoRyouki/ExtremeReactors2) —— 被压缩的多方块机器本体
- [ZeroCore2](https://github.com/ZeroNoRyouki/ZeroCore2) —— 多块控制器基础框架
- [CompactMekanismMachinesPlus](https://github.com/nanaios/CompactMekanismMachinesPlus) —— 单方块压缩思路参考
- [NeoForge](https://docs.neoforged.net/) —— 模组框架与官方文档

## 📜 许可证

本模组为个人学习 / 社区作品，复用 ER 与 ZeroCore 的 API 与纹理，请遵循其原始开源许可。
