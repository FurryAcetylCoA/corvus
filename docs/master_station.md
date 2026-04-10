# Master Station 主控站

## 现状
- master station 的全部功能已由 `SatelliteStation` 覆盖（控制寄存器、StateBus 队列、同步树标志等）。
- 不再单独实现 `MasterStation` 模块；master 核直接实例化/复用 `SatelliteStation(p.nMBus)`。
- master station 只暴露 `MBus` 队列，不暴露 `SBus` 队列；因此 master 的本地卫星站地址空间会比 satellite 更小。
- master 核不需要 `stateBusBufferFullInterrupt`，通过轮询本地 MBus 队列计数即可获知写队列是否满。
