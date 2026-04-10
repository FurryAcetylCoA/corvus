# Top 顶层架构

`src/main/scala/corvus/Top.scala`

`Top` 是 corvus 的系统顶层，负责把多个 XiangShan 核、控制站、状态总线、同步树和片上外设网络拼成一个完整 SoC。它本身不提供具体的存储器/外设模型，只定义对外的 AXI4 端口，由更外层环境决定接入真实设备还是仿真设备。

## 默认配置（`CorvusConfig`）
- `numSCore = 8`，因此 `numTotalCore = numSCore + 1 = 9`。
- 角色划分：1 个 master core + 8 个 satellite core。
- `nMBus = 2`，即 2 条经过 master + satellite 的 StateBus 环。
- `nSBus = 2`，即 2 条只经过 satellite 的 StateBus 环。
- `nStateBus = nMBus + nSBus = 4`，即 satellite station 本地可见 4 条 StateBus。
- `simCoreDBusDataWidth = 64`，SatelliteStation 的控制口按 64-bit AXI4 Slave 组织。
- `satelliteIRQNum = 5`，卫星核的队列满中断挂到 PLIC 的第 5 号外部中断。

## 顶层 IO
- `memory`：外部内存控制器端口组，来自 `memoryBus.io.controllers`。
- `peripheral`：系统对外设地址空间的总出口。
- `simUart`：每个核一个模拟 UART 端口，地址固定为 `0x310b0000`。
- `riscv_rst_vec`：每个核的复位向量输入。
- `rtc_clock`：低速 RTC 输入时钟。
- `extIntrs`：每个核一组 64 路外部中断输入。

## 主要子模块

### XiangShan 核阵列
- 先实例化 1 个 `XSTopWrap` 作为模板。
- 再通过 `CloneModuleAsRecord` 克隆出剩余 `numTotalCore - 1` 个核的端口记录。
- 每个核都连接：
  - 独立的内存 AXI 端口。
  - 独立的外设 AXI 端口。
  - 一组 CLINT/PLIC/复位/中断/NMI/debug 线。

### 每核本地外设总线 `peripheralBuses`
每个核前面都放置一套 1-master/5-controller 的 `AXI4Network`，把 XiangShan 的 peripheral 访问拆分到 5 个目标：

| 控制器 | 地址范围 | 作用 |
| - | - | - |
| `clint` | `0x38000000` - `0x3800ffff` | 机器定时器/软件中断 |
| `plic` | `0x3c000000` - `0x3fffffff` | 平台中断控制器 |
| `sat` | `0x30000000` - `0x3000ffff` | SatelliteStation 控制口 |
| `peripheral` | 其余外设地址窗口 | 向系统外部外设总线转发 |
| `simuart` | `0x310b0000` - `0x310b0fff` | 每核模拟 UART |

其中 `peripheral` 的地址窗口由 `peripheralAddressSets` 定义，等价于：
- 接收大部分非 CLINT/PLIC/satellite/simuart 的外设访问。
- 刻意从低地址空间中挖掉 `0x30000000` 和 `0x310b0000` 这两个局部设备窗口，避免地址重叠。

### 控制站 `SatelliteStation`
- `Top` 对每个核都实例化 1 个 `SatelliteStation`。
- `masterStation` 服务 master core，并实例化为 `SatelliteStation(p.nMBus)`。
- `satelliteStations` 服务全部 satellite core，并实例化为 `SatelliteStation(p.nStateBus)`。
- master 站与 satellite 站复用同一个模块，但本地暴露的 StateBus 队列数量不同，差异见 `docs/master_station.md`。
- 其 AXI4 Slave 通过本地外设总线的 `sat` 窗口暴露给对应核。

顶层对 `SatelliteStation` 做了一层 AXI 适配：
- 地址进入模块前减去 `satelliteAddr.base`，把总线绝对地址转换成站内相对地址。
- `CtrlAXI4Slave` 本身不携带 AXI ID，因此顶层用 `satArId`/`satAwId` 暂存 AR/AW 的 ID，并在 R/B 通道回填。

### StateBus 环网
- `mBusRingNodes` 组织为 `numTotalCore x nMBus` 的二维数组。
- 对每个核、每条 `MBus`，连接关系为：
  - `sat.io.fromCoreStateBusPort(i) -> ring.io.fromCore`
  - `ring.io.toCore -> sat.io.toCoreStateBusPort(i)`
  - `ring.io.nodeId := sat.io.nodeId`
- 随后对 `mBusRingNodes` 做转置，把同一编号的 `MBus` 串成一个首尾相连的环：
  - `curr.io.toNext <> next.io.fromPrev`

- `sBusRingNodes` 组织为 `numSCore x nSBus` 的二维数组，只对应 satellite 节点。
- 对每个 satellite 核、每条 `SBus`，连接关系为：
  - `sat.io.fromCoreStateBusPort(nMBus + i) -> ring.io.fromCore`
  - `ring.io.toCore -> sat.io.toCoreStateBusPort(nMBus + i)`
  - `ring.io.nodeId := sat.io.nodeId`
- 随后对 `sBusRingNodes` 做转置，把同一编号的 `SBus` 串成一个只覆盖 satellite 节点的首尾相连的环。

因此系统里共有两组独立环：`nMBus` 条 master+satellite 共享的 `MBus` 环，以及 `nSBus` 条仅由 satellite 组成的 `SBus` 环。

### 同步树 `SyncTree`
- master core 对应的 `SatelliteStation`：
  - `outSyncFlag` 驱动 `syncTree.io.masterIn`，即广播树根输入。
  - `inSyncFlag` 读取 `syncTree.io.masterOut`，即归并树根输出。
- satellite core 对应的 `SatelliteStation`：
  - `outSyncFlag` 送入 `syncTree.io.slaveIn(idx - 1)`。
  - `inSyncFlag` 读取 `syncTree.io.slaveOut(idx - 1)`。

这与 `docs/sync_tree.md` 的约定一致：master 负责广播，satellite 负责上报并接收广播结果。

### CLINT / PLIC
- `clintBus`：1 个 controller，多 master，所有核都映射到 `0x38000000`。
- `standAloneClint`：实际 CLINT 设备，输出 `time` 和每核的定时器中断。
- `standAlonePlicLMs`：每个核前各放 1 个独立 PLIC，基地址都为 `0x3c000000`。

PLIC 的连接方式有两个特点：
- 每个 PLIC 的 `extIntrs.head` 由对应 `io.extIntrs(idx)` 驱动，表示该站看到的外部中断源。
- PLIC 内部会生成面向全部 hart 的中断线，但顶层只取与当前 `idx` 对应的 2 根中断线接回当前核。

此外，只有 satellite core 额外接入 `stateBusBufferFullInterrupt`：
- `standAlonePlicLMs(idx).extIntrs.head(p.satelliteIRQNum - 1) := satelliteStations(idx - 1).io.stateBusBufferFullInterrupt`
- master core 不接这根线，而是按 `docs/master_station.md` 中描述采用轮询。

### 系统外设总线 `peripheralOutBus`
- 这是一个多 master、单 controller 的 `AXI4Network`。
- 每个核本地外设总线的 `peripheral` 控制器都转发到这里。
- `peripheralOutBus.io.controllers.head <> io.peripheral`，因此真正的片外设备由 SoC 外层决定。

### 内存总线 `memoryBus`
- 也是一个多 master、单 controller 的 `AXI4Network`。
- 默认只建一个控制器 `mem0`，覆盖从 `0x80000000` 开始的连续地址空间。
- 每个核独占 `0x10000000` 字节的地址窗口：
  - core0: `0x80000000` - `0x8fffffff`
  - core1: `0x90000000` - `0x9fffffff`
  - ...
- 当前内存 AXI 参数固定为 `addrBits = 48`、`dataBits = 256`、`idBits = 14`、`maxTransferBytes = 64`。

## 核心连线总览

### 每个 XiangShan 核的输入
- `hartId := idx.U`
- `clintTime := standAloneClint.io.time`
- `riscv_rst_vec := io.riscv_rst_vec(idx)`
- `clint` 中断来自 `standAloneClintLM.int(idx)`
- `plic` 中断来自当前站 PLIC 的本地 2 根输出
- `nmi` / `debug` 全部拉成 0

### 每个 XiangShan 核的总线
- `memory` 口直接连接 `memoryBus.io.cores(idx)`。
- `peripheral` 口连接 `peripheralBuses(idx).cores.head`。
- `dma` 口当前用全 0 `WireDefault` 占位，没有接入实际 DMA 设备。

## 运行时行为摘要
1. 核发起内存访问时，经 `memoryBus` 路由到系统内存控制器。
2. 核发起外设访问时，先经过本地外设总线，根据地址命中 CLINT、PLIC、SatelliteStation、模拟 UART 或系统外设出口。
3. SatelliteStation 通过控制寄存器向核暴露 SyncTree 标志和 StateBus 队列。
4. `mBusRingNodes` 与 `sBusRingNodes` 负责把各站写入的 `StateBusPacket` 在对应类别的环上传播到目标节点。
5. `SyncTree` 负责 master 到 satellite 的同步广播，以及 satellite 到 master 的状态归并。

## 设计边界
- `Top` 只描述 SoC 内部结构，不自带真实 RAM、Flash、UART 模型。
- 所有片外设备都通过 `io.memory`、`io.peripheral`、`io.simUart` 暴露给上层环境。
- 因此，FPGA 顶层、仿真顶层或其他验证环境都可以在不改 `Top` 的前提下替换外部设备实现。