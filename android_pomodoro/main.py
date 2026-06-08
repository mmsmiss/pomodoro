#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
番茄钟 — Kivy Android 版
========================

专为 Android 手机设计的番茄钟应用。
使用 Kivy 框架编写，可通过 Buildozer 打包为 .apk 安装到手机。

在电脑上测试:  pip install kivy && python main.py
打包 Android APK:  buildozer android debug

功能:
  🍅 25 分钟工作 / ☕ 5 分钟短休 / 😴 15 分钟长休
  📊 圆形进度环，直观展示剩余时间
  📳 计时结束手机振动提醒
  🔊 提示音提醒
  🔆 计时中屏幕常亮，防止自动锁屏
  🔢 番茄计数，跟踪专注周期
"""

import os
import sys
import json
import struct
import wave
import math
import tempfile

from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.checkbox import CheckBox
from kivy.uix.widget import Widget
from kivy.clock import Clock
from kivy.graphics import Color, Line
from kivy.properties import (
    NumericProperty, StringProperty, ListProperty,
    BooleanProperty, ObjectProperty,
)
from kivy.core.audio import SoundLoader
from kivy.core.window import Window
from kivy.metrics import dp
from kivy.utils import platform
from kivy.lang import Builder

# ── 常量 ──────────────────────────────────────────
WORK_TIME = 25 * 60       # 25 分钟
SHORT_BREAK = 5 * 60      # 5 分钟
LONG_BREAK = 15 * 60      # 15 分钟
TOMATOES_PER_CYCLE = 4    # 每轮 4 个番茄

# ── Android 环境检测 ───────────────────────────────
IS_ANDROID = platform == 'android'
IS_IOS = platform == 'ios'


# ═══════════════════════════════════════════════════
#  提示音生成 (纯 Python，无需外部音频文件)
# ═══════════════════════════════════════════════════

def _generate_beep_wav(path: str, freq: float = 880, duration: float = 0.25):
    """生成一个短促的 WAV 提示音文件"""
    sample_rate = 44100
    n_samples = int(sample_rate * duration)
    with wave.open(path, 'w') as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(sample_rate)
        for i in range(n_samples):
            t = i / sample_rate
            # 带衰减的正弦波
            envelope = max(0, 1 - t / duration)
            value = int(32767 * 0.5 * envelope *
                        math.sin(2 * math.pi * freq * t))
            f.writeframes(struct.pack('<h', value))


def _generate_triple_beep(path: str):
    """生成三段渐升提示音"""
    sample_rate = 44100
    total_duration = 0.6  # 总长 0.6 秒
    n_samples = int(sample_rate * total_duration)
    with wave.open(path, 'w') as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(sample_rate)
        for i in range(n_samples):
            t = i / sample_rate
            # 三声短促 beep
            if t < 0.12:
                freq = 660 + t * 2000  # 上升音调
                envelope = 1.0
            elif t < 0.15:
                freq = 0
                envelope = 0
            elif t < 0.27:
                freq = 880 + (t - 0.15) * 2000
                envelope = 1.0
            elif t < 0.30:
                freq = 0
                envelope = 0
            elif t < 0.42:
                freq = 1100 + (t - 0.30) * 2000
                envelope = 1.0
            else:
                freq = 0
                envelope = 0
            value = int(32767 * 0.5 * envelope *
                        math.sin(2 * math.pi * freq * t))
            f.writeframes(struct.pack('<h', value))


# ═══════════════════════════════════════════════════
#  Android 原生功能封装
# ═══════════════════════════════════════════════════

class AndroidFeatures:
    """封装 Android 特有功能：振动、屏幕常亮、通知"""

    _vibrator = None
    _wakelock_enabled = False

    @staticmethod
    def vibrate(ms: int = 500):
        """让手机振动指定毫秒"""
        if IS_ANDROID:
            try:
                from jnius import autoclass
                Context = autoclass('android.content.Context')
                activity = autoclass('org.kivy.android.PythonActivity').mActivity
                if AndroidFeatures._vibrator is None:
                    AndroidFeatures._vibrator = activity.getSystemService(
                        Context.VIBRATOR_SERVICE)
                # Android 振动需要 VIBRATE 权限
                AndroidFeatures._vibrator.vibrate(ms)
            except Exception:
                pass

    @staticmethod
    def keep_screen_on(enable: bool):
        """控制屏幕常亮（防止计时中自动锁屏）"""
        if not IS_ANDROID:
            return
        try:
            from jnius import autoclass
            activity = autoclass('org.kivy.android.PythonActivity').mActivity
            LayoutParams = autoclass(
                'android.view.WindowManager$LayoutParams')
            if enable:
                activity.getWindow().addFlags(
                    LayoutParams.FLAG_KEEP_SCREEN_ON)
            else:
                activity.getWindow().clearFlags(
                    LayoutParams.FLAG_KEEP_SCREEN_ON)
            AndroidFeatures._wakelock_enabled = enable
        except Exception:
            pass

    @staticmethod
    def send_notification(title: str, body: str):
        """发送 Android 系统通知"""
        if not IS_ANDROID:
            return
        try:
            from jnius import autoclass
            from android.runnable import run_on_ui_thread

            Context = autoclass('android.content.Context')
            NotificationBuilder = autoclass(
                'android.app.Notification$Builder')
            NotificationManager = autoclass(
                'android.app.NotificationManager')
            # 通知渠道 ID (Android 8.0+)
            channel_id = "pomodoro_timer"
            app_context = autoclass(
                'org.kivy.android.PythonActivity').mActivity

            # 兼容旧版 Android 的应用图标
            try:
                icon = app_context.getApplicationInfo().icon
            except Exception:
                icon = 17301514  # android.R.drawable.ic_dialog_info

            builder = NotificationBuilder(app_context, channel_id)
            builder.setContentTitle(title)
            builder.setContentText(body)
            builder.setSmallIcon(icon)
            builder.setAutoCancel(True)
            builder.setPriority(1)  # HIGH

            nm = app_context.getSystemService(
                Context.NOTIFICATION_SERVICE)
            nm.notify(1, builder.build())
        except Exception:
            pass


# ═══════════════════════════════════════════════════
#  圆形进度环 Widget
# ═══════════════════════════════════════════════════

class ProgressCircle(Widget):
    """在画布上绘制圆形进度环"""

    fraction = NumericProperty(1.0)   # 0 ~ 1，进度比例
    arc_color = ListProperty([0.9529, 0.5451, 0.6588, 1.0])  # 默认红色

    def __init__(self, bg_color=None, line_width=14, **kwargs):
        super().__init__(**kwargs)
        if bg_color is None:
            bg_color = [0.192, 0.196, 0.267, 1.0]  # #313244
        self.bg_color = bg_color
        self.line_width = line_width
        self.bind(fraction=self._redraw, arc_color=self._redraw,
                  size=self._redraw, pos=self._redraw)

    def _redraw(self, *args):
        self.canvas.clear()
        w = self.width
        h = self.height
        if w <= 0 or h <= 0:
            return

        # 半径取短边，留出边距
        margin = self.line_width / 2 + dp(4)
        r = min(w, h) / 2 - margin
        cx = w / 2
        cy = h / 2

        with self.canvas:
            # 背景圆环
            Color(*self.bg_color)
            Line(circle=(cx, cy, r), width=self.line_width)

            # 进度弧（从 12 点方向顺时针）
            if self.fraction > 0.001:
                Color(*self.arc_color)
                angle_sweep = 360 * self.fraction
                # Kivy 中 0°=3点钟，逆时针；我们从 90° (12点) 顺时针画
                # 即从 90° 到 90°-sweep (即顺时针扫过)
                Line(
                    circle=(cx, cy, r, 90, 90 - angle_sweep),
                    width=self.line_width,
                    cap='round',
                )


# ═══════════════════════════════════════════════════
#  KV 语言布局
# ═══════════════════════════════════════════════════

KV = r'''
#: import dp kivy.metrics.dp

<PomodoroRoot>:
    orientation: 'vertical'
    padding: dp(16)
    spacing: dp(6)
    canvas.before:
        Color:
            rgba: 0.118, 0.118, 0.180, 1.0    # #1e1e2e
        Rectangle:
            pos: self.pos
            size: self.size

    # ── 标题 ──
    Label:
        text: '🍅 番茄钟'
        font_size: dp(24)
        bold: True
        color: 0.804, 0.839, 0.957, 1   # #cdd6f4
        size_hint_y: None
        height: dp(44)

    # ── 进度环 + 时间 + 状态 ──
    FloatLayout:
        id: ring_area
        size_hint_y: 0.55

        ProgressCircle:
            id: progress_circle
            size_hint: None, None
            size: dp(260), dp(260)
            pos_hint: {'center_x': 0.5, 'center_y': 0.5}

        Label:
            id: timer_text
            text: '25:00'
            font_name: 'DroidSansMono.ttf' if app.is_android else 'Consolas'
            font_size: dp(46)
            bold: True
            color: 0.804, 0.839, 0.957, 1
            pos_hint: {'center_x': 0.5, 'center_y': 0.56}

        Label:
            id: status_text
            text: '准备开始'
            font_size: dp(12)
            color: 0.424, 0.443, 0.533, 1   # #6c7086
            pos_hint: {'center_x': 0.5, 'center_y': 0.38}

    # ── 模式标签 ──
    Label:
        id: mode_label
        text: '工作模式'
        font_size: dp(13)
        color: 0.651, 0.678, 0.784, 1   # #a6adc8
        size_hint_y: None
        height: dp(22)

    # ── 模式切换按钮 ──
    BoxLayout:
        spacing: dp(8)
        size_hint_y: None
        height: dp(44)
        pos_hint: {'center_x': 0.5}

        Button:
            id: btn_work
            text: '🍅  工作'
            on_release: app.switch_mode('work')
            background_normal: ''
            background_color: 0.953, 0.545, 0.659, 1   # active red
            color: 0.118, 0.118, 0.180, 1
            font_size: dp(13)
            bold: True
            size_hint_x: 1

        Button:
            id: btn_short
            text: '☕  短休'
            on_release: app.switch_mode('short_break')
            background_normal: ''
            background_color: 0.271, 0.275, 0.353, 1   # dim
            color: 0.729, 0.741, 0.871, 1
            font_size: dp(13)
            bold: True
            size_hint_x: 1

        Button:
            id: btn_long
            text: '😴  长休'
            on_release: app.switch_mode('long_break')
            background_normal: ''
            background_color: 0.271, 0.275, 0.353, 1
            color: 0.729, 0.741, 0.871, 1
            font_size: dp(13)
            bold: True
            size_hint_x: 1

    # ── 控制按钮 ──
    BoxLayout:
        spacing: dp(10)
        size_hint_y: None
        height: dp(52)

        Button:
            id: btn_start
            text: '▶  开始'
            on_release: app.start_timer()
            background_normal: ''
            background_color: 0.796, 0.651, 0.969, 1   # purple
            color: 0.118, 0.118, 0.180, 1
            font_size: dp(16)
            bold: True
            size_hint_x: 1

        Button:
            id: btn_pause
            text: '⏸  暂停'
            on_release: app.pause_timer()
            background_normal: ''
            background_color: 0.588, 0.588, 0.706, 1   # dim
            color: 0.424, 0.443, 0.533, 1
            font_size: dp(16)
            bold: True
            size_hint_x: 1
            disabled: True

        Button:
            id: btn_reset
            text: '↺  重置'
            on_release: app.reset_timer()
            background_normal: ''
            background_color: 0.976, 0.886, 0.686, 1   # yellow
            color: 0.118, 0.118, 0.180, 1
            font_size: dp(16)
            bold: True
            size_hint_x: 1

    # ── 番茄计数 ──
    Label:
        id: tomato_label
        text: '⚪⚪⚪⚪'
        font_size: dp(18)
        color: 0.976, 0.886, 0.686, 1   # #f9e2af
        size_hint_y: None
        height: dp(26)

    Label:
        id: tomato_detail
        text: '第 1/4 个番茄  |  已完成 0 个'
        font_size: dp(12)
        color: 0.651, 0.678, 0.784, 1
        size_hint_y: None
        height: dp(20)

    # ── 选项 ──
    BoxLayout:
        spacing: dp(24)
        size_hint_y: None
        height: dp(36)
        pos_hint: {'center_x': 0.5}

        BoxLayout:
            size_hint_x: None
            width: dp(110)
            spacing: dp(6)
            CheckBox:
                id: chk_sound
                active: True
                on_active: app.toggle_sound(self.active)
                color: 0.796, 0.651, 0.969, 1
                size_hint_x: None
                width: dp(28)
            Label:
                text: '🔊 提示音'
                font_size: dp(12)
                color: 0.729, 0.741, 0.871, 1
                size_hint_x: None
                width: dp(76)

        BoxLayout:
            size_hint_x: None
            width: dp(110)
            spacing: dp(6)
            CheckBox:
                id: chk_vibrate
                active: True
                on_active: app.toggle_vibrate(self.active)
                color: 0.796, 0.651, 0.969, 1
                size_hint_x: None
                width: dp(28)
            Label:
                text: '📳 振动'
                font_size: dp(12)
                color: 0.729, 0.741, 0.871, 1
                size_hint_x: None
                width: dp(76)

    # ── 底部间距 ──
    Widget:
        size_hint_y: None
        height: dp(8)
'''


# ═══════════════════════════════════════════════════
#  主布局根 Widget
# ═══════════════════════════════════════════════════

class PomodoroRoot(BoxLayout):
    """根布局，用于 KV 中引用"""
    pass


# ═══════════════════════════════════════════════════
#  番茄钟 App 主类
# ═══════════════════════════════════════════════════

class PomodoroApp(App):
    """番茄钟 Kivy 应用"""

    # ── 属性 ──
    time_left = WORK_TIME
    total_time = WORK_TIME
    current_mode = "work"       # work | short_break | long_break
    timer_state = "idle"        # idle | running | paused
    tomatoes_completed = 0
    sound_enabled = True
    vibrate_enabled = True
    clock_event = None          # Clock 事件引用
    _beep_wav_path = None       # 生成的提示音文件路径

    # ── 模式色表 ──
    MODE_COLORS = {
        "work":        [0.9529, 0.5451, 0.6588, 1.0],  # #f38ba8 红
        "short_break": [0.6510, 0.8902, 0.6314, 1.0],  # #a6e3a1 绿
        "long_break":  [0.5373, 0.7059, 0.9804, 1.0],  # #89b4fa 蓝
    }

    MODE_LABELS = {
        "work":        "工作模式",
        "short_break": "短休息模式",
        "long_break":  "长休息模式",
    }

    MODE_TIMES = {
        "work":        WORK_TIME,
        "short_break": SHORT_BREAK,
        "long_break":  LONG_BREAK,
    }

    # ── 生命周期 ──────────────────────────────────

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.is_android = IS_ANDROID

    def build(self):
        """Kivy 入口：加载布局并返回根 widget"""
        self.icon = '🍅'
        self.title = '番茄钟'

        # 设置窗口背景色
        Window.clearcolor = (0.118, 0.118, 0.180, 1)

        # 加载 KV 布局
        Builder.load_string(KV)
        self.root = PomodoroRoot()

        # 生成提示音
        self._setup_sound()

        # 恢复设置
        self._load_settings()

        # 绑定 Android 返回键
        if self.is_android:
            try:
                from kivy.core.window import Window
                Window.bind(on_keyboard=self._on_keyboard)
            except Exception:
                pass

        # 首次绘制
        Clock.schedule_once(lambda dt: self._update_all(), 0.1)

        return self.root

    def on_start(self):
        """App 启动完成后"""
        self._update_all()

    def on_pause(self):
        """App 切到后台（Android）"""
        # 计时中保持屏幕常亮的设置在后台不生效，这里不做特殊处理
        # Kivy 的 Clock 在后台仍会运行
        return True  # 允许暂停

    def on_resume(self):
        """App 从后台恢复（Android）"""
        self._update_all()

    def on_stop(self):
        """App 退出"""
        self._cancel_clock()
        self._save_settings()
        # 清理提示音文件
        if self._beep_wav_path and os.path.exists(self._beep_wav_path):
            try:
                os.remove(self._beep_wav_path)
            except Exception:
                pass

    # ── 键盘事件（Android 返回键等）─────────────────

    def _on_keyboard(self, window, key, *args):
        """处理 Android 返回键"""
        if key == 27:  # ESC / Back
            # 不退出，而是切到后台
            if self.is_android:
                try:
                    from jnius import autoclass
                    activity = autoclass(
                        'org.kivy.android.PythonActivity').mActivity
                    activity.moveTaskToBack(True)
                except Exception:
                    self.stop()
            return True
        return False

    # ── 提示音初始化 ───────────────────────────────

    def _setup_sound(self):
        """生成提示音 WAV 文件"""
        try:
            # 使用应用目录存放提示音
            dest = os.path.join(
                tempfile.gettempdir(), 'pomodoro_beep.wav')
            _generate_triple_beep(dest)
            self._beep_wav_path = dest
        except Exception:
            self._beep_wav_path = None

    def _play_sound(self):
        """播放提示音"""
        if not self.sound_enabled:
            return
        if self._beep_wav_path and os.path.exists(self._beep_wav_path):
            try:
                sound = SoundLoader.load(self._beep_wav_path)
                if sound:
                    sound.play()
            except Exception:
                pass

    # ── 设置持久化 ─────────────────────────────────

    @property
    def _settings_path(self):
        """配置文件路径"""
        data_dir = '.'
        if self.is_android:
            try:
                from jnius import autoclass
                activity = autoclass(
                    'org.kivy.android.PythonActivity').mActivity
                data_dir = activity.getFilesDir().getAbsolutePath()
            except Exception:
                pass
        return os.path.join(data_dir, 'pomodoro_settings.json')

    def _load_settings(self):
        try:
            path = self._settings_path
            if not os.path.exists(path):
                return
            with open(path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            self.sound_enabled = data.get('sound_enabled', True)
            self.vibrate_enabled = data.get('vibrate_enabled', True)
            self.tomatoes_completed = data.get('tomatoes_completed', 0)

            # 更新 CheckBox
            root = self.root
            if root:
                root.ids.chk_sound.active = self.sound_enabled
                root.ids.chk_vibrate.active = self.vibrate_enabled
        except Exception:
            pass

    def _save_settings(self):
        try:
            data = {
                'sound_enabled': self.sound_enabled,
                'vibrate_enabled': self.vibrate_enabled,
                'tomatoes_completed': self.tomatoes_completed,
            }
            with open(self._settings_path, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    # ── 模式切换 ───────────────────────────────────

    def switch_mode(self, mode: str):
        """切换到指定模式"""
        self._cancel_clock()

        self.current_mode = mode
        self.timer_state = "idle"
        self.time_left = self.MODE_TIMES[mode]
        self.total_time = self.MODE_TIMES[mode]

        # 关闭屏幕常亮
        AndroidFeatures.keep_screen_on(False)

        self._update_all()
        self._save_settings()

    # ── 定时器控制 ─────────────────────────────────

    def start_timer(self):
        """开始 / 继续"""
        if self.timer_state == "running":
            return

        self.timer_state = "running"

        # 屏幕常亮
        AndroidFeatures.keep_screen_on(True)

        # 启动每秒 tick
        if self.clock_event is None:
            self.clock_event = Clock.schedule_interval(
                self._tick, 1.0)

        self._update_all()

    def pause_timer(self):
        """暂停"""
        if self.timer_state != "running":
            return

        self.timer_state = "paused"
        self._cancel_clock()

        # 关闭屏幕常亮
        AndroidFeatures.keep_screen_on(False)

        self._update_all()

    def reset_timer(self):
        """重置"""
        self._cancel_clock()
        self.timer_state = "idle"
        self.time_left = self.MODE_TIMES[self.current_mode]
        self.total_time = self.MODE_TIMES[self.current_mode]

        # 关闭屏幕常亮
        AndroidFeatures.keep_screen_on(False)

        self._update_all()

    # ── 定时器核心 ─────────────────────────────────

    def _tick(self, dt):
        """每秒回调"""
        if self.timer_state != "running":
            return

        if self.time_left > 0:
            self.time_left -= 1
            self._update_display()
        else:
            self._on_timer_finished()

    def _cancel_clock(self):
        """取消定时器"""
        if self.clock_event is not None:
            Clock.unschedule(self.clock_event)
            self.clock_event = None

    def _on_timer_finished(self):
        """计时结束"""
        self._cancel_clock()
        self.timer_state = "idle"

        # 关闭屏幕常亮
        AndroidFeatures.keep_screen_on(False)

        # 提醒
        self._play_sound()
        if self.vibrate_enabled:
            # 振动三次
            AndroidFeatures.vibrate(200)
            Clock.schedule_once(lambda dt: AndroidFeatures.vibrate(200), 0.3)
            Clock.schedule_once(lambda dt: AndroidFeatures.vibrate(200), 0.6)

        # 系统通知
        if self.current_mode == "work":
            AndroidFeatures.send_notification(
                "🍅 工作结束！",
                "休息一下吧，你已经完成了 "
                f"{self.tomatoes_completed + 1} 个番茄！"
            )
        else:
            AndroidFeatures.send_notification(
                "🍅 休息结束！",
                "开始新的工作番茄吧！"
            )

        # 自动切换
        if self.current_mode == "work":
            self.tomatoes_completed += 1
            if self.tomatoes_completed % TOMATOES_PER_CYCLE == 0:
                self.switch_mode("long_break")
            else:
                self.switch_mode("short_break")
        else:
            self.switch_mode("work")

        self._save_settings()
        self._update_all()

    # ── 设置切换 ───────────────────────────────────

    def toggle_sound(self, active: bool):
        """切换提示音"""
        self.sound_enabled = active
        self._save_settings()

    def toggle_vibrate(self, active: bool):
        """切换振动"""
        self.vibrate_enabled = active
        self._save_settings()

    # ── UI 更新 ────────────────────────────────────

    def _update_all(self):
        """更新全部 UI"""
        self._update_display()
        self._update_mode_buttons()
        self._update_control_buttons()
        self._update_tomato_label()

    def _update_display(self):
        """更新进度环和时间文字"""
        root = self.root
        if not root:
            return

        # 进度环
        circle = root.ids.progress_circle
        fraction = self.time_left / self.total_time if self.total_time > 0 else 0
        circle.fraction = fraction
        circle.arc_color = self.MODE_COLORS.get(
            self.current_mode, self.MODE_COLORS["work"])

        # 时间文本
        minutes = self.time_left // 60
        seconds = self.time_left % 60
        root.ids.timer_text.text = f"{minutes:02d}:{seconds:02d}"

        # 状态文字
        status_map = {
            "idle": "准备开始",
            "running": "进行中…",
            "paused": "已暂停",
        }
        root.ids.status_text.text = status_map.get(self.timer_state, "")

        # 模式标签
        root.ids.mode_label.text = self.MODE_LABELS.get(
            self.current_mode, "")

    def _update_mode_buttons(self):
        """高亮当前模式按钮"""
        root = self.root
        if not root:
            return

        ACTIVE_COLORS = {
            "work":        [0.953, 0.545, 0.659, 1],
            "short_break": [0.651, 0.890, 0.631, 1],
            "long_break":  [0.537, 0.706, 0.980, 1],
        }
        DIM_BG = [0.271, 0.275, 0.353, 1]
        DIM_FG = [0.729, 0.741, 0.871, 1]
        DARK_FG = [0.118, 0.118, 0.180, 1]

        for mode, btn_id in [
            ("work", "btn_work"),
            ("short_break", "btn_short"),
            ("long_break", "btn_long"),
        ]:
            btn = root.ids[btn_id]
            if mode == self.current_mode:
                btn.background_color = ACTIVE_COLORS[mode]
                btn.color = DARK_FG
            else:
                btn.background_color = DIM_BG
                btn.color = DIM_FG

    def _update_control_buttons(self):
        """根据状态启用/禁用控制按钮"""
        root = self.root
        if not root:
            return

        PURPLE = [0.796, 0.651, 0.969, 1]
        PEACH  = [0.980, 0.702, 0.529, 1]
        YELLOW = [0.976, 0.886, 0.686, 1]
        DIM_BG = [0.588, 0.588, 0.706, 1]
        DIM_FG = [0.424, 0.443, 0.533, 1]
        DARK_FG = [0.118, 0.118, 0.180, 1]

        start_btn = root.ids.btn_start
        pause_btn = root.ids.btn_pause
        reset_btn = root.ids.btn_reset

        if self.timer_state == "running":
            start_btn.disabled = True
            start_btn.background_color = DIM_BG
            start_btn.color = DIM_FG
            start_btn.text = "▶  开始"

            pause_btn.disabled = False
            pause_btn.background_color = PEACH
            pause_btn.color = DARK_FG

            reset_btn.disabled = False
            reset_btn.background_color = YELLOW
            reset_btn.color = DARK_FG

        elif self.timer_state == "paused":
            start_btn.disabled = False
            start_btn.background_color = PURPLE
            start_btn.color = DARK_FG
            start_btn.text = "▶  继续"

            pause_btn.disabled = True
            pause_btn.background_color = DIM_BG
            pause_btn.color = DIM_FG

            reset_btn.disabled = False
            reset_btn.background_color = YELLOW
            reset_btn.color = DARK_FG

        else:  # idle
            start_btn.disabled = False
            start_btn.background_color = PURPLE
            start_btn.color = DARK_FG
            start_btn.text = "▶  开始"

            pause_btn.disabled = True
            pause_btn.background_color = DIM_BG
            pause_btn.color = DIM_FG

            reset_btn.disabled = False
            reset_btn.background_color = YELLOW
            reset_btn.color = DARK_FG

    def _update_tomato_label(self):
        """更新底部番茄计数"""
        root = self.root
        if not root:
            return

        pos_in_cycle = self.tomatoes_completed % TOMATOES_PER_CYCLE
        filled = "🍅" * pos_in_cycle
        empty = "⚪" * (TOMATOES_PER_CYCLE - pos_in_cycle)
        root.ids.tomato_label.text = filled + empty

        root.ids.tomato_detail.text = (
            f"第 {pos_in_cycle + 1}/{TOMATOES_PER_CYCLE} 个番茄  "
            f"|  已完成 {self.tomatoes_completed} 个"
        )


# ═══════════════════════════════════════════════════
#  入口
# ═══════════════════════════════════════════════════

if __name__ == '__main__':
    PomodoroApp().run()
