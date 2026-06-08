# 🍅 番茄钟 - Android 版

基于 Kivy 框架的 Android 原生番茄钟应用。

## ✨ 功能

- 🍅 25 分钟专注工作 + ☕ 5 分钟短休 + 😴 15 分钟长休
- 📊 圆形进度环，直观展示剩余时间
- 📳 计时结束手机振动提醒
- 🔊 提示音提醒
- 🔆 计时中屏幕常亮，不会自动锁屏
- 🔢 番茄计数，跟踪每轮专注周期
- 💾 设置自动保存（提示音/振动开关、已完成番茄数）

## 📱 安装到手机

### 方法一：下载 APK 直接安装（推荐）

打包好的 APK 文件在 `bin/` 目录下，传到手机直接安装即可。

### 方法二：自己打包 APK

#### 前提条件

- **Linux 环境**（WSL / 虚拟机 / 云服务器 / Google Colab 均可）
- Python 3.8+

#### 1. 安装依赖

```bash
# 安装 buildozer
pip install buildozer

# 安装系统依赖 (Ubuntu/Debian)
sudo apt update
sudo apt install -y \
    git zip unzip openjdk-17-jdk \
    python3-pip autoconf libtool pkg-config \
    zlib1g-dev libncurses5-dev libncursesw5-dev \
    libtinfo5 cmake libffi-dev libssl-dev \
    autoconf automake libtool
```

#### 2. 打包

```bash
cd android_pomodoro

# 构建 debug APK（首次约 10-30 分钟，需下载 SDK/NDK）
buildozer android debug

# APK 输出在: bin/pomodoro-1.0-arm64-v8a-debug.apk
```

#### 3. 安装到手机

```bash
# USB 连接手机（开启开发者模式 + USB 调试）
buildozer android deploy run

# 或者手动安装
adb install bin/pomodoro-1.0-arm64-v8a-debug.apk
```

### 方法三：用 Google Colab 免费打包

如果没有 Linux 环境，可以用 Google Colab 在线打包：

1. 访问 https://colab.research.google.com/
2. 上传整个 `android_pomodoro/` 文件夹到 Colab
3. 运行以下代码：

```python
# 安装 buildozer
!pip install buildozer

# 安装系统依赖
!sudo apt update
!sudo apt install -y git zip unzip openjdk-17-jdk python3-pip \
    autoconf libtool pkg-config zlib1g-dev libncurses5-dev \
    libncursesw5-dev libtinfo5 cmake libffi-dev libssl-dev

# 打包
!cd android_pomodoro && buildozer android debug

# 下载 APK
from google.colab import files
files.download('android_pomodoro/bin/pomodoro-1.0-arm64-v8a-debug.apk')
```

## 🖥 在电脑上测试

```bash
pip install kivy
python main.py
```

## 📂 文件说明

| 文件 | 说明 |
|------|------|
| `main.py` | Kivy 应用主代码 |
| `buildozer.spec` | Android APK 打包配置 |
| `README.md` | 本说明文件 |

## 🔧 自定义

- 修改工作时长：编辑 `main.py` 顶部 `WORK_TIME` 等常量（单位：秒）
- 修改应用图标：放置 `icon.png` (512×512) 到此目录，在 `buildozer.spec` 中启用
- 修改配色：编辑 `MODE_COLORS` 字典中的 RGBA 值

## ⚠️ 常见问题

| 问题 | 解决 |
|------|------|
| 首次打包很慢 | 正常，需下载 Android SDK/NDK（~1-2GB），之后会快很多 |
| `VIBRATE` 权限报错 | 已配置，忽略警告即可 |
| 通知不显示 | 首次启动时允许通知权限 |
| 无法安装 APK | 手机设置 → 安全 → 允许安装未知来源应用 |
