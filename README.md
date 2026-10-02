# WiFi 雷达（WiFi Radar）

安卓 WiFi 网络扫描与分析工具，用于网络调试、信道规划和家庭/办公室网络优化。纯 Java 原生实现，深色主题，支持手机与 VR 头显（ADB 侧载）。

![版本](https://img.shields.io/badge/version-4.1-blue) ![平台](https://img.shields.io/badge/platform-Android%208.0%2B-green) ![API](https://img.shields.io/badge/API-26%2B-orange)

## 功能特性

- **扫描列表**：附近 WiFi 热点，显示 SSID、BSSID、信号强度（dBm）、信道、频段（2.4G / 5G / 6G）、技术标准（WiFi 4/5/6/6E/7、802.11a/b/g/n/ac/ax/be）、加密类型；支持按信号 / 名称 / 信道排序
- **信道分布图**：可视化各信道占用与信号强弱，单根热点为长方形柱，同信道叠加显示为梯形堆叠 + "×N" 角标
  - 分页视图：全部 / 2.4G / 5G 低（36-64）/ 5G 中（100-144）/ 5G 高（149-165）/ 6G，顶部按钮或左右滑动切换
  - 点击柱子：高亮 + 信号气泡；左右滑动切换相邻热点（可开启"精细滑动"逐根选择）
  - 长按柱子：弹出居中详情大菜单（SSID / BSSID / 加密 / 信道 / 信号 / 技术标准 / 信号历史折线）
  - 选中期间冻结图表刷新，避免选择被采样打断
- **信号历史曲线**：任意热点信号强度随时间变化（每 3 秒采样，最近 60 点）
- **本机信息**：本机 IPv4、网关、DNS、已连接 SSID/BSSID、连接速率（Mbps）、当前信号、连接频段与技术标准
- **户型图信号采样**：导入户型图/平面图作为底图，点击记录当前信号强度，生成信号分布采样点，可撤销 / 清空 / 导出热力图 PNG
- **自动扫描**：默认每 3 秒自动扫描一次，可切换手动

## 设置

- 长按弹出详情菜单的时长（0.25s / 0.45s / 0.7s / 1.0s）
- 精细滑动选择：选中热点后，在图表空白区域滑动可逐根切换相邻热点

## 关于权限

- 需要**定位权限**：这是 Android 8.0+ 扫描 WiFi 网络的**系统硬性要求**（不授权系统会直接禁止 `startScan`），但本 App **不使用 GPS 定位数据**，只读取 WiFi 扫描结果，可在无 GPS 的设备（如 Quest 头显）正常使用
- 需要 WiFi 状态权限（读取扫描结果）

## 安装

### 手机（Android 8.0+）

1. 下载 Release 中的 `WiFiRadar-v4.1.apk` 传到手机（微信 / 数据线 / 网盘均可）
2. 点击安装。若提示"未知来源"，在设置中允许该应用安装即可

### Quest 头显（ADB 侧载）

1. 电脑安装 [platform-tools](https://developer.android.com/tools/releases/platform-tools)（含 adb）或者Minimal ADB and Fastboot
2. Quest 开启开发者模式，USB 连接电脑，在头显内允许 USB 调试
3. 执行：

```
adb install "你的文件路径"
```

4. 安装后在 Quest 的**应用库（App Library）**中找到"WiFi 雷达"，以 2D 窗口方式运行
5. 首次运行在头显内授予定位权限（Quest 系统也会要求，授予后即可扫描）
6. 如果看不到应用，在 Quest 设置中把"未知来源（Unknown Sources）"筛选打开

> 说明：Quest 系统对第三方应用的 WiFi 扫描是否完全开放取决于系统版本。若在 Quest 上扫描结果为空，建议在普通安卓手机上使用（功能完全相同）；Quest 上仍可查看本机信息等。

## 重新构建

环境要求：JDK 17、Android SDK（compileSdk 34）、Gradle 8.x。

```
gradle assembleDebug
```

产物输出：`app/build/outputs/apk/debug/app-debug.apk`

发布正式版时请自行用 `keytool` 生成签名 keystore，并配置 `signingConfigs`。

## 项目结构

```
wifi-radar/
├── app/
│   └── src/main/
│       ├── java/com/oscar/wifiradar/
│       │   ├── MainActivity.java       # 主界面与交互
│       │   ├── WifiScanner.java        # WiFi 扫描封装
│       │   ├── ScanResultItem.java     # 热点数据模型与解析
│       │   ├── NetInfo.java            # 本机 IP/网关/DNS/连接信息
│       │   ├── ChannelChartView.java   # 信道分布图（点击/滑动/长按交互）
│       │   ├── ChannelPageAdapter.java # 信道分页适配器（ViewPager2）
│       │   ├── SignalChartView.java    # 信号历史曲线
│       │   ├── FloorPlanView.java      # 户型图底图与采样层
│       │   └── ApAdapter.java          # 扫描列表适配器
│       └── res/                        # 布局/颜色/图标资源
├── build.gradle                        # Gradle 构建配置
└── README.md
```

## 版本历史

| 版本 | 说明 |
|------|------|
| v4.1 | 3.8 滑动交互 + v4.0 性能优化（列表指纹去重、信道图不重绑、Path 复用） |
| v4.0 | 性能优化：列表内容去重、信道页按需更新、绘制 Path 复用 |
| v3.9 | 滑动实时跟手（后被 v4.1 回退为 v3.8 的有序切换） |
| v3.8 | 选中热点期间冻结图表刷新 |
| v3.7 | 长按弹出居中详情大菜单（可被系统返回键关闭） |
| v3.6 | 叠加柱角标显示与让位优化 |
| v3.5 | 同信道叠加显示为梯形堆叠 |
| v3.1 | 纯点击 + 滑动选择（去掉上滑手势） |
| v2.x | 拖拽 / 上滑 / 详情面板等交互迭代 |
| v1.x | 首个可用版本：扫描列表 + 信道分布 + 本机信息 |

## License

此项目当前未指定许可证，保留所有权利。如需开源协议（如 MIT / Apache-2.0），请创建 Issue 或联系作者。
