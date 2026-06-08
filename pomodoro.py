#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
桌面番茄钟 — Python + tkinter 实现
无需额外依赖，双击或 python pomodoro.py 即可运行。
"""

import tkinter as tk
import json
import sys
from pathlib import Path

# ── 常量 ──────────────────────────────────────────
WORK_TIME = 25 * 60       # 工作时间: 25 分钟
SHORT_BREAK = 5 * 60      # 短休息:   5 分钟
LONG_BREAK = 15 * 60      # 长休息:  15 分钟
TOMATOES_PER_CYCLE = 4    # 每轮 4 个番茄


class PomodoroTimer:
    """番茄钟主应用"""

    def __init__(self):
        self.window = tk.Tk()
        self.window.title("🍅 番茄钟")
        self.window.geometry("420x580")
        self.window.resizable(False, False)
        self.window.configure(bg="#1e1e2e")

        # ── 状态 ──
        self.time_left = WORK_TIME
        self.total_time = WORK_TIME
        self.current_mode = "work"       # work | short_break | long_break
        self.timer_state = "idle"        # idle | running | paused
        self.tomatoes_completed = 0
        self.always_on_top = tk.BooleanVar(value=False)
        self.after_id = None

        self._build_ui()
        self._load_settings()
        self._center_window()

    # ═══════════════════════════════════════════════
    #  UI 构建
    # ═══════════════════════════════════════════════

    def _build_ui(self):
        # --- 标题 ---
        self.title_label = tk.Label(
            self.window, text="🍅 番茄钟",
            font=("Microsoft YaHei", 26, "bold"),
            bg="#1e1e2e", fg="#cdd6f4",
        )
        self.title_label.pack(pady=(25, 5))

        # --- 画布 (圆形进度条 + 倒计时) ---
        self.canvas = tk.Canvas(
            self.window, width=280, height=280,
            bg="#1e1e2e", highlightthickness=0,
        )
        self.canvas.pack(pady=5)

        # --- 模式标签 ---
        self.mode_label = tk.Label(
            self.window, text="工作模式",
            font=("Microsoft YaHei", 13),
            bg="#1e1e2e", fg="#a6adc8",
        )
        self.mode_label.pack(pady=(0, 10))

        # --- 模式切换按钮 ---
        mode_frame = tk.Frame(self.window, bg="#1e1e2e")
        mode_frame.pack(pady=5)

        btn_style = {
            "font": ("Microsoft YaHei", 10, "bold"),
            "relief": "flat",
            "padx": 14,
            "pady": 6,
            "cursor": "hand2",
            "borderwidth": 0,
        }

        self.work_btn = tk.Button(
            mode_frame, text="🍅  工作", **btn_style,
            command=lambda: self._switch_mode("work"),
        )
        self.work_btn.pack(side="left", padx=4)

        self.short_break_btn = tk.Button(
            mode_frame, text="☕  短休", **btn_style,
            command=lambda: self._switch_mode("short_break"),
        )
        self.short_break_btn.pack(side="left", padx=4)

        self.long_break_btn = tk.Button(
            mode_frame, text="😴  长休", **btn_style,
            command=lambda: self._switch_mode("long_break"),
        )
        self.long_break_btn.pack(side="left", padx=4)

        # --- 控制按钮 ---
        ctrl_frame = tk.Frame(self.window, bg="#1e1e2e")
        ctrl_frame.pack(pady=18)

        ctrl_style = {
            "font": ("Microsoft YaHei", 14, "bold"),
            "relief": "flat",
            "padx": 22,
            "pady": 8,
            "cursor": "hand2",
            "borderwidth": 0,
        }

        self.start_btn = tk.Button(
            ctrl_frame, text="▶  开始", **ctrl_style,
            command=self._start,
        )
        self.start_btn.pack(side="left", padx=5)

        self.pause_btn = tk.Button(
            ctrl_frame, text="⏸  暂停", **ctrl_style,
            command=self._pause, state="disabled",
        )
        self.pause_btn.pack(side="left", padx=5)

        self.reset_btn = tk.Button(
            ctrl_frame, text="↺  重置", **ctrl_style,
            command=self._reset,
        )
        self.reset_btn.pack(side="left", padx=5)

        # --- 番茄计数 ---
        self.tomato_label = tk.Label(
            self.window, text="",
            font=("Microsoft YaHei", 11),
            bg="#1e1e2e", fg="#f9e2af",
        )
        self.tomato_label.pack(pady=(10, 10))

        # --- 置顶开关 ---
        top_cb = tk.Checkbutton(
            self.window, text="📌 窗口置顶",
            variable=self.always_on_top,
            command=self._toggle_topmost,
            font=("Microsoft YaHei", 10),
            bg="#1e1e2e", fg="#bac2de",
            selectcolor="#313244",
            activebackground="#1e1e2e",
            activeforeground="#cdd6f4",
        )
        top_cb.pack(pady=(5, 20))

        # 首次绘制
        self._draw_progress()
        self._update_tomato_label()
        self._update_mode_buttons()
        self._update_control_buttons()

    # ═══════════════════════════════════════════════
    #  绘制
    # ═══════════════════════════════════════════════

    def _draw_progress(self):
        """绘制圆形进度条和倒计时文字"""
        self.canvas.delete("all")
        cx, cy, r = 140, 140, 110

        # 底色圆环
        self.canvas.create_oval(
            cx - r, cy - r, cx + r, cy + r,
            outline="#313244", width=14,
        )

        # 进度弧 (12 点钟方向顺时针)
        if self.total_time > 0:
            angle = (self.time_left / self.total_time) * 360
        else:
            angle = 0

        mode_colors = {
            "work": "#f38ba8",        # 红
            "short_break": "#a6e3a1", # 绿
            "long_break": "#89b4fa",  # 蓝
        }
        color = mode_colors.get(self.current_mode, "#f38ba8")

        if angle > 0:
            self.canvas.create_arc(
                cx - r, cy - r, cx + r, cy + r,
                start=90, extent=-angle,
                outline=color, width=14, style="arc",
            )

        # 倒计时数字
        minutes = self.time_left // 60
        seconds = self.time_left % 60
        time_str = f"{minutes:02d}:{seconds:02d}"

        self.canvas.create_text(
            cx, cy - 12, text=time_str,
            font=("Consolas", 44, "bold"),
            fill="#cdd6f4",
        )

        # 状态文字
        status_map = {
            "idle":    "准备开始",
            "running": "进行中…",
            "paused":  "已暂停",
        }
        status_text = status_map.get(self.timer_state, "")
        self.canvas.create_text(
            cx, cy + 42, text=status_text,
            font=("Microsoft YaHei", 11),
            fill="#6c7086",
        )

    def _update_tomato_label(self):
        """更新底部番茄计数显示"""
        pos_in_cycle = self.tomatoes_completed % TOMATOES_PER_CYCLE
        filled = "🍅" * pos_in_cycle
        empty = "⚪" * (TOMATOES_PER_CYCLE - pos_in_cycle)
        cycle_num = self.tomatoes_completed // TOMATOES_PER_CYCLE + 1
        text = (
            f"{filled}{empty}  "
            f"第 {pos_in_cycle + 1}/{TOMATOES_PER_CYCLE} 个番茄  "
            f"|  已完成 {self.tomatoes_completed} 个"
        )
        self.tomato_label.config(text=text)

    # ═══════════════════════════════════════════════
    #  定时器逻辑
    # ═══════════════════════════════════════════════

    def _switch_mode(self, mode: str):
        """切换到指定模式"""
        self._cancel_timer()

        self.current_mode = mode
        self.timer_state = "idle"

        times = {
            "work": WORK_TIME,
            "short_break": SHORT_BREAK,
            "long_break": LONG_BREAK,
        }
        self.time_left = times[mode]
        self.total_time = times[mode]

        mode_names = {
            "work": "工作模式",
            "short_break": "短休息模式",
            "long_break": "长休息模式",
        }
        self.mode_label.config(text=mode_names[mode])

        self._update_mode_buttons()
        self._update_control_buttons()
        self._draw_progress()

    def _start(self):
        """开始 / 继续计时"""
        if self.timer_state == "running":
            return
        self.timer_state = "running"
        self._update_control_buttons()
        self._draw_progress()
        self._tick()

    def _pause(self):
        """暂停计时"""
        if self.timer_state != "running":
            return
        self.timer_state = "paused"
        self._cancel_timer()
        self._update_control_buttons()
        self._draw_progress()

    def _reset(self):
        """重置当前计时"""
        self._cancel_timer()
        self.timer_state = "idle"

        times = {
            "work": WORK_TIME,
            "short_break": SHORT_BREAK,
            "long_break": LONG_BREAK,
        }
        self.time_left = times[self.current_mode]
        self.total_time = times[self.current_mode]

        self._update_control_buttons()
        self._draw_progress()

    def _tick(self):
        """每秒回调"""
        if self.timer_state != "running":
            return

        if self.time_left > 0:
            self.time_left -= 1
            self._draw_progress()
            self.after_id = self.window.after(1000, self._tick)
        else:
            self._on_timer_finished()

    def _cancel_timer(self):
        """取消等待中的定时回调"""
        if self.after_id is not None:
            self.window.after_cancel(self.after_id)
            self.after_id = None

    def _on_timer_finished(self):
        """计时结束"""
        self.timer_state = "idle"
        self.after_id = None

        # 播放提示音
        self._play_sound()

        # 闪烁窗口提醒
        self._flash_window()

        # 自动切换模式
        if self.current_mode == "work":
            self.tomatoes_completed += 1
            self._update_tomato_label()

            if self.tomatoes_completed % TOMATOES_PER_CYCLE == 0:
                self._switch_mode("long_break")
            else:
                self._switch_mode("short_break")
        else:
            # 休息结束 → 切回工作
            self._switch_mode("work")

        self._update_control_buttons()
        self._draw_progress()

    # ═══════════════════════════════════════════════
    #  辅助
    # ═══════════════════════════════════════════════

    def _play_sound(self):
        """播放系统提示音"""
        try:
            import winsound
            winsound.MessageBeep(winsound.MB_ICONEXCLAMATION)
        except Exception:
            # 非 Windows 或失败时输出响铃字符
            sys.stdout.write("\a")
            sys.stdout.flush()

    def _flash_window(self):
        """短暂置顶窗口以引起注意"""
        self.window.attributes("-topmost", True)
        self.window.after(
            600,
            lambda: self.window.attributes("-topmost", self.always_on_top.get()),
        )

    def _toggle_topmost(self):
        self.window.attributes("-topmost", self.always_on_top.get())
        self._save_settings()

    def _update_mode_buttons(self):
        """高亮当前模式的按钮"""
        active_colors = {
            "work":        "#f38ba8",
            "short_break": "#a6e3a1",
            "long_break":  "#89b4fa",
        }
        dim_bg = "#45475a"
        dim_fg = "#bac2de"

        for mode, btn in [
            ("work", self.work_btn),
            ("short_break", self.short_break_btn),
            ("long_break", self.long_break_btn),
        ]:
            if mode == self.current_mode:
                c = active_colors[mode]
                btn.config(bg=c, fg="#1e1e2e", activebackground=c)
            else:
                btn.config(bg=dim_bg, fg=dim_fg, activebackground="#585b70")

    def _update_control_buttons(self):
        """根据状态启用/禁用控制按钮"""
        state = self.timer_state

        if state == "running":
            self.start_btn.config(state="disabled", text="▶  开始",
                                  bg="#585b70", fg="#6c7086")
            self.pause_btn.config(state="normal",
                                  bg="#fab387", fg="#1e1e2e")
            self.reset_btn.config(state="normal",
                                  bg="#f9e2af", fg="#1e1e2e")
        elif state == "paused":
            self.start_btn.config(state="normal", text="▶  继续",
                                  bg="#a6e3a1", fg="#1e1e2e")
            self.pause_btn.config(state="disabled",
                                  bg="#585b70", fg="#6c7086")
            self.reset_btn.config(state="normal",
                                  bg="#f9e2af", fg="#1e1e2e")
        else:  # idle
            self.start_btn.config(state="normal", text="▶  开始",
                                  bg="#cba6f7", fg="#1e1e2e")
            self.pause_btn.config(state="disabled",
                                  bg="#585b70", fg="#6c7086")
            self.reset_btn.config(state="normal",
                                  bg="#f9e2af", fg="#1e1e2e")

        self._draw_progress()

    def _center_window(self):
        """窗口居中"""
        self.window.update_idletasks()
        w = self.window.winfo_width()
        h = self.window.winfo_height()
        sw = self.window.winfo_screenwidth()
        sh = self.window.winfo_screenheight()
        x = (sw - w) // 2
        y = (sh - h) // 2
        self.window.geometry(f"+{x}+{y}")

    # ═══════════════════════════════════════════════
    #  设置持久化
    # ═══════════════════════════════════════════════

    @property
    def _settings_path(self) -> Path:
        return Path(__file__).parent / "pomodoro_settings.json"

    def _load_settings(self):
        path = self._settings_path
        if not path.exists():
            return
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            self.always_on_top.set(data.get("always_on_top", False))
            if data.get("always_on_top"):
                self.window.attributes("-topmost", True)
            if data.get("geometry"):
                self.window.geometry(data["geometry"])
        except Exception:
            pass

    def _save_settings(self):
        try:
            data = {
                "always_on_top": self.always_on_top.get(),
                "geometry": self.window.geometry(),
            }
            self._settings_path.write_text(
                json.dumps(data, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
        except Exception:
            pass

    # ═══════════════════════════════════════════════
    #  生命周期
    # ═══════════════════════════════════════════════

    def run(self):
        self.window.protocol("WM_DELETE_WINDOW", self._on_close)
        self.window.mainloop()

    def _on_close(self):
        self._save_settings()
        self._cancel_timer()
        self.window.destroy()


# ── 入口 ─────────────────────────────────────────
if __name__ == "__main__":
    app = PomodoroTimer()
    app.run()
