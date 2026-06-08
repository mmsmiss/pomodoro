[app]

# ── 应用基本信息 ──────────────────────────────────
title = 番茄钟
package.name = pomodoro
package.domain = org.cc.pomodoro
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,json,wav,ttf,otf
version = 1.0

# ── 依赖 ──────────────────────────────────────────
requirements = python3==3.10,kivy

# ── 屏幕方向 ──────────────────────────────────────
orientation = portrait

# ── 全屏 / 状态栏 ─────────────────────────────────
fullscreen = 0

# ── Android 权限 ──────────────────────────────────
# VIBRATE:    计时结束振动
# WAKE_LOCK:  计时中保持屏幕常亮
# POST_NOTIFICATIONS: Android 13+ 通知权限
android.permissions = VIBRATE,WAKE_LOCK,POST_NOTIFICATIONS

# ── Android API 级别 ──────────────────────────────
android.api = 33
android.minapi = 24
android.ndk = 25b
android.accept_sdk_license = True

# ── 启动画面 ──────────────────────────────────────
# (可选) 放置 splash.png 在此目录即可自动使用
# presplash.filename = %(source.dir)s/presplash.png

# ── 日志级别 ──────────────────────────────────────
log_level = 2

# ── 复制 APK 到项目目录 ───────────────────────────
android.allow_download_in_build_dir = True

# ── 应用架构 ──────────────────────────────────────
android.archs = arm64-v8a

# ── 签名（发布用） ────────────────────────────────
# release = 1 时取消注释下面两行并填写
# android.release_keystore = ./pomodoro.keystore
# android.release_keyalias = pomodoro

[buildozer]

# ── 构建工具 ──────────────────────────────────────
log_level = 2
warn_on_root = 1
