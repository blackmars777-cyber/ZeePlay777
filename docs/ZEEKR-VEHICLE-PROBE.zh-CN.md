# 极氪车辆数据探针

探针是独立的只读诊断应用，用于检查车机权限、CarSensor 接口和实际读数。
它不依赖 CarPlay 主应用，不包含投屏页面、音视频编解码器、媒体桥或 CarPlay 认证资产。

## 构建与安装

```sh
bash scripts/build-zeekr-vehicle-probe.sh
adb install -r vehicleprobe/build/outputs/standalone/zeekr-vehicle-probe-debug.apk
```

包名：`com.shihab.diplay.zeekr.vehicleprobe`。
探针与 `com.shihab.diplay.hudtest` 主应用分别安装，不互相覆盖。
更新已安装的探针需要兼容的签名。

## 使用

1. 停车后打开探针，按提示授予车辆数据权限。
2. 检查速度、挡位、电量和续航的原始值、时间戳与可用性。
3. 将读数与车机仪表核对；数值缺失或单位无法确认时，不作为主程序上报依据。
4. 如需共享报告，先检查并移除个人信息，再主动导出。

数据含义与单位依赖固件，不能把某一车型的映射套用于所有 Android 车机。
探针保存的诊断选项不启用主应用的车辆数据上报。独立探针的权限结果也不能证明主应用具有相同权限。

探针不向车辆写入属性，不控制车辆、HUD 或仪表，不绕过系统授权。
主程序的准入条件和上报规则见[车辆数据说明](ZEEKR-VEHICLE-DATA.zh-CN.md)。
