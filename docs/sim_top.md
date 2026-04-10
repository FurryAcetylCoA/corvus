# SimTop 仿真顶层架构

`src/test/scala/corvus/SimTop.scala`

`SimTop` 是 corvus 的仿真封装顶层。它实例化正式 SoC 顶层 `Top`，再补上仿真专用的 RAM、Flash、UART 和 RTC 时钟，从而形成一个可直接生成 Verilog、可被测试平台驱动的完整系统。

与 `Top` 的分工如下：
- `Top` 定义 SoC 的外部接口和内部互连。
- `SimTop` 为这些接口接入具体的仿真模型。

## 顶层 IO
- `uart = Vec(numTotalCore + 1, UARTIO)`
- 含义：
  - `uart(0)`：主外设总线上的独立 UART。
  - `uart(1)` 到 `uart(numTotalCore)`：每个核各自的模拟 UART16550。

默认配置下 `numTotalCore = p.numSCore + 1 = 9`，因此总共导出 10 路 UART 口。

## 主要子模块

### 正式 SoC 顶层 `corvusTop`
- `val corvusTop = Module(new Top)`
- `SimTop` 不修改 `Top` 的内部结构，只从外部把 `memory`、`peripheral`、`simUart`、`extIntrs`、`riscv_rst_vec` 和 `rtc_clock` 接起来。

### 主外设总线仿真网络 `peripheralDeviceBus`
`Top` 的 `io.peripheral` 只是系统外设出口，`SimTop` 在外面再接一层 1-master/2-controller 的 `AXI4Network`：

| 控制器 | 地址范围 | 作用 |
| - | - | - |
| `uart` | `0x40600000` - `0x4060003f` | 主 UART |
| `flash` | `0x10000000` - `0x1fffffff` | 启动 Flash |

连接方式：
- `peripheralDeviceBus.io.cores.head <> corvusTop.io.peripheral`

因此所有从 `Top` 转发出来、命中这两个地址窗口的访问，都会落到仿真设备模型。

### 主 UART
- 通过 `StandAloneUART(false, 0x40600000L, 6, 64, 1)` 构建。
- AXI 侧接到 `peripheralDeviceBus` 的 `uart` 控制器。
- 串口 IO 侧接到 `io.uart.head`。

这一路 UART 属于系统普通外设，不等同于每核局部的 `simUart`。

### 启动 Flash
- 通过 `StandAloneFlash(false, 0x10000000L, 28, 64, 1)` 构建。
- AXI 侧接到 `peripheralDeviceBus` 的 `flash` 控制器。
- 没有额外导出的 IO，主要用于给各核提供启动映像访问。

同时，`SimTop` 把所有核的 `riscv_rst_vec` 固定为 `0x10000000`，因此各核复位后默认从这片 Flash 启动。

### 每核模拟 UART16550
- 数量：`numTotalCore` 个，每个核一个。
- 设备模型：`StandAloneUART16550`。
- 地址：全部挂在各自核可见的 `0x310b0000` 窗口。
- 数据位宽跟随 `corvusTop.io.peripheral.params.dataBits`。

连接关系：
- `corvusTop.io.simUart(idx) <> simUart16550LMs(idx).axi4node.get.getWrappedValue`
- `simUart16550s(idx).io <> io.uart(idx + 1)`

这组设备正好对应 `Top` 里每核本地外设总线上的 `simuart` 控制器。

## 内存系统仿真
`Top` 把内存控制器端口暴露在 `io.memory`，`SimTop` 则为每个内存 slave node 动态实例化一个 `AXI4MemorySlave`：

- 容量来自对应 slave 地址窗口的 `mask + 1`。
- `useBlackBox = true`，使用黑盒 RAM 模型。
- `dynamicLatency = false`，固定延迟行为。

连接方式：
- 遍历 `corvusTop.memoryBus.inner.wrapper.slaveNodes` 生成内存模型。
- 再把 `simMemIO.io_axi4.elements.head._2` 通过 `viewAs[AXI4Bundle]` 接到 `corvusTop.io.memory`。

这样 `Top` 内部的所有内存访问都可以落到仿真 RAM，而无需改动正式顶层代码。

## 中断与复位连接

### 外部中断
`SimTop` 先把全部 `extIntrs` 清零，再把每个核对应 UART16550 的中断接到 `p.uartIRQNum - 1`：

- `corvusTop.io.extIntrs := 0.U.asTypeOf(...)`
- 对每个核：`topIntrs(p.uartIRQNum - 1) := simUart16550.io_int.head.head`

因此：
- 每个核都能收到自己那一路模拟 UART16550 的外部中断。
- 其余外部中断源在仿真顶层默认关闭。
- 主外设总线上的独立 UART 不通过这里回灌中断。

### 复位向量
- `corvusTop.io.riscv_rst_vec.foreach(_ := 0x10000000L.U)`
- 各核统一从 Flash 基地址启动。

## RTC 时钟生成
`Top` 需要一个较低频的 `rtc_clock` 输入，`SimTop` 用主时钟分频生成：

- `rtcClockDiv = 100`
- `rtcTickCycle = 50`
- 计数器每 50 个主时钟周期翻转一次 `rtcClock`

因此 `rtcClock` 的完整周期为 100 个主时钟周期。该时钟送入 `corvusTop.io.rtc_clock`，随后在 `Top` 内部再做正沿采样，驱动 CLINT 的 `rtcTick`。

## 数据通路总结
1. 核访问内存时，`Top.memoryBus -> SimTop.AXI4MemorySlave`。
2. 核访问系统外设时，`Top.io.peripheral -> peripheralDeviceBus -> UART/Flash`。
3. 核访问本地模拟 UART 时，`Top.io.simUart(idx) -> StandAloneUART16550(idx)`。
4. UART16550 中断从仿真设备回灌到 `Top.io.extIntrs(idx)`。
5. 启动地址和 Flash 基地址保持一致，便于直接加载镜像启动。

## Verilog 生成入口
`SimTop.scala` 末尾的 `Elaborate` 对象会导出 SystemVerilog：
- 顶层模块：`new SimTop`
- 额外 firtool 选项：
  - `-disable-all-randomization`
  - `-strip-debug-info`

这说明 `SimTop` 不只是测试时的 Scala 封装，也被当作仿真 RTL 的直接生成入口。