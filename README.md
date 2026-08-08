# Screen-Mirroring 📱→🖥️

一个跨平台的手机投屏软件：手机作为发射端，PC 与 Android TV 均可作为接收端，
**接收端可像扩展屏幕那样全屏显示**，TV 端还支持选择指定显示器或镜像到所有屏。

> ⚠️ **声明：本项目的代码与文档由 AI（TRAE / GLM 模型）生成。**
> 代码未经充分的人工审查与测试，可能存在缺陷或安全风险，请自行评估后使用。
> 因使用本代码造成的任何直接或间接损失，作者不承担任何责任。

## 功能特性

- 📱 **发射端（Android App）**：基于 `MediaProjection` 抓屏 + `MediaCodec` H.264 硬编码 + TCP 推流，无需 root。
- 🖥️ **PC 接收端（Python）**：
  - 主版 `receiver.py`：PyAV 解码 + SDL2 渲染，窗口可拖到副屏后按 `F` 全屏（`FULLSCREEN_DESKTOP`），**即作为扩展屏显示**。
  - 备选版 `receiver_ffplay.py`：仅依赖系统 ffmpeg/ffplay，零额外依赖。
- 📺 **Android TV 接收端（Android App）**：
  - 列出所有显示器（内置 + HDMI 外接），D-Pad 选择目标屏全屏投屏。
  - 「镜像到所有屏」：同一份数据分发给所有解码器，多屏同步显示。
  - 支持 HDMI 热插拔，自动刷新列表并停掉已移除的屏。
- 🔌 自定义轻量帧协议，TCP 长连接，断流自动等待重连。

## 架构与协议

```
┌─────────────┐    H.264 over TCP     ┌──────────────────┐
│  发射端手机  │  ─────────────────▶  │  接收端 (PC/TV)   │
│ MediaProjection│  帧协议见下        │ 解码 + 全屏渲染    │
│ MediaCodec     │                    │                   │
└─────────────┘                      └──────────────────┘
```

每个数据帧格式（与 [FrameProtocol.kt](sender-android/app/src/main/java/com/screencast/sender/FrameProtocol.kt) 和 [frame_protocol.py](receiver-pc/frame_protocol.py) 对齐）：

```
| magic(1B=0x55) | payloadLen(4B, big-endian) | timestampUs(8B, big-endian) | H.264 NAL payload |
```

## 项目结构

```
screencast/
├── sender-android/        # 手机发射端 (Android App)
│   └── app/src/main/java/com/screencast/sender/
│       ├── FrameProtocol.kt        # 帧协议
│       ├── H264Sender.kt           # TCP 推流
│       ├── ScreenCastService.kt    # 抓屏+编码+推流前台服务
│       └── MainActivity.kt         # 主界面
├── receiver-pc/           # PC 接收端 (Python)
│   ├── frame_protocol.py           # 帧协议解析
│   ├── receiver.py                 # PyAV + SDL2 主版
│   ├── receiver_ffplay.py          # ffplay 备选版
│   └── requirements.txt
└── tvreceiver-android/    # Android TV 接收端 (Android App)
    └── app/src/main/java/com/screencast/tv/
        ├── FrameProtocol.kt        # 帧协议解析
        ├── H264Decoder.kt          # MediaCodec 硬解
        ├── ScreenReceiverServer.kt # TCP 接收服务
        ├── CastPresentation.kt     # 在任意 Display 上全屏显示
        └── MainActivity.kt         # 多屏选择/镜像
```

## 快速开始

### 1) PC 接收端

```bash
cd receiver-pc
pip install -r requirements.txt          # av, PySDL2, numpy；另需系统安装 SDL2 原生库
python receiver.py --port 8855
# 或零依赖版（仅需系统装 ffmpeg）：
python receiver_ffplay.py --port 8855
```

快捷键：`F` 全屏 / `ESC`、`Q` 退出。
**扩展屏用法**：把投屏窗口拖到副屏（扩展显示器），按 `F` 全屏到该屏。

### 2) 手机发射端

用 Android Studio 打开 `sender-android/`，连真机 Run。
打开 App → 填接收端 IP 与端口 8855 →「开始投屏」→ 系统弹窗授权屏幕录制。

### 3) Android TV 接收端

用 Android Studio 打开 `tvreceiver-android/`，安装到电视/电视盒子。
打开后主界面显示本机 IP 并列出所有显示器，D-Pad 选屏或选「镜像到所有屏」。

## 使用流程

1. PC/电视 与手机连同一 WiFi。
2. 先启动接收端（PC `python receiver.py` 或打开 TV App），记下局域网 IP。
3. 手机 App 填该 IP →「开始投屏」→ 授权。
4. 画面投到接收端并全屏显示，手机断开后接收端自动等待重连。

## 技术参数

- 发射端默认：长边 1080p、4Mbps、30fps、I 帧间隔 2s（见 `ScreenCastService.kt`）。
- PC 主版渲染：RGB24 + SDL2 纹理流式更新 + VSync。
- TV 端解码：MediaCodec 硬解，首次收到 SPS/PPS 才 configure，断流后自动重置等待新关键帧。

## 许可证

本项目基于 [GNU General Public License v3.0](LICENSE) 开源。

## 贡献

欢迎提 Issue 或 Pull Request。由于本仓库由 AI 生成初始代码，欢迎人工审查、改进与测试反馈。

## 免责声明

- 本项目仅用于学习与局域网内合法的屏幕投射场景。
- 投屏内容版权归原作者所有，请勿用于侵犯他人隐私或版权的用途。
- 代码未经充分人工审查，使用风险自负。
