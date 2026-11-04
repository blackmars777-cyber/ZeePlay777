# CarPlay · 极氪 OS 7.2.5 定制版

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

**面向 Zeekr OS 7.2.5 车机适配的 CarPlay 应用**，支持有线与无线连接，重点优化极氪车机上的界面体验、媒体中心联动、热点连接和原生车辆数据接入。

本项目 fork 自 [DiPlay](https://github.com/shihabal3amri/DiPlay)。

## 相比原项目的主要改动

| 方向 | 定制内容 |
| --- | --- |
| 界面 | 参考极氪 OS 7 视觉语言，采用浅色／深色主题、克制的蓝色强调、线性图标和分组列表；适配横屏、分屏与大字体。 |
| 设置 | 重整设置、搜索与首次向导，隐藏 BYD／DiLink 专属配置，突出极氪车机功能。 |
| 媒体中心 | 同步 CarPlay 歌曲、封面和播放进度，转发支持的车机播放指令，并提供自动切换 CarPlay 音源的选项。 |
| 车机热点 | 检测到原厂热点服务时优先使用该服务，复用车机保存的配置；这条服务路径不需要 ADB。 |
| 车辆数据 | 通过匹配的 CarSensor 接口读取实测电量、动态续航、车速与挡位，按需向 iPhone 上报。 |
| 配置体验 | 媒体中心、自动选源与播放控制可分别配置；需要新会话的修改显示重新连接提示，由用户决定何时重连。 |

## Zeekr OS 7.2.5 适配说明

**本分支以 Zeekr OS 7.2.5 为适配目标。** 界面按实际窗口大小和系统保留区域布局，不复制原厂状态栏、空调 Dock 或车辆控制组件，也不重绘 CarPlay 投屏内容。

相同 OS 版本不代表所有车型、固件接口都一致。媒体中心、热点和车辆数据功能以车机实际服务及权限为准；车辆数据解析仍限定于已匹配的车型／固件组合，不会伪造缺失或未经验证的读数。具体范围见[车辆数据适配说明](docs/ZEEKR-VEHICLE-DATA.zh-CN.md)。

## 使用方式

1. 将 **Zeekr 版本安装到车机**，不是 iPhone。包名为 `com.shihab.diplay.hudtest`。
2. 选择 USB、车机热点、Wi-Fi Direct 或现有 Wi-Fi 网络，完成配对并授予所需权限。
3. 在 **设置 → 车辆 → 车机联动** 中配置媒体中心、自动选源和播放控制。
4. 原生车辆数据可读后，再按需开启 **高级 → 极氪车辆数据（实验性）** 中的电量／续航及车速上报；车速上报还需要开启位置共享。

覆盖安装旧版本时需使用兼容签名，以保留设置与配对数据。独立车辆数据探针是另一个应用，不是 CarPlay 主程序。

## 构建极氪版本

```sh
./gradlew -Pdiplay.edition=zeekr :mobile:assembleDebug
```

输出文件：`mobile/build/outputs/apk/debug/mobile-debug.apk`。

普通源码／CI 构建不包含 CarPlay 认证资产。完整本地构建需按[极氪构建说明](docs/ZEEKR-BUILD.md)提供外部运行资产；认证文件和签名密钥不应提交到仓库。

## 文档与问题反馈

- [极氪版构建与打包](docs/ZEEKR-BUILD.md)
- [媒体中心接入](docs/ZEEKR-MEDIA-SOURCE.md)
- [免 ADB 原厂热点服务](docs/ZEEKR-HOTSPOT.md)
- [车辆数据与实车验收](docs/ZEEKR-VEHICLE-DATA.zh-CN.md)
- [界面与组件规范](docs/design/ZEEKR-OS72-HMI.md)

遇到连接或车机联动问题，可在 **设置 → 诊断** 中导出报告，并附上车型、完整 OS／固件版本、iPhone／iOS 版本、连接方式、复现步骤和故障时间。分享前请检查报告中的个人信息。

## 来源与许可

本 fork 保留 DiPlay 的现有开源许可与归属声明，详见 [LICENSE](LICENSE) 和[第三方声明](docs/THIRD_PARTY_NOTICES.md)。本项目为独立 fork，与极氪或 Apple 无官方隶属关系。
