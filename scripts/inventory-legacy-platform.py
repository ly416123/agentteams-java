#!/usr/bin/env python3
"""G12 前置：旧平台配置与历史数据盘点工具（台账镜像口径，只读）。

数据源：corp-agent MySQL（at_* / de_* 表）与 corp-agent application*.yaml。
产出：output/legacy-inventory-<ts>/detail/*.json（明细，gitignore）
      docs/inventory/<date>-legacy-inventory.md（汇总，入库，脱敏）。
"""

from __future__ import annotations

# 退出码：0=正常完成，1=降级完成（存在表缺失），2=连接失败
EXIT_OK = 0
EXIT_DEGRADED = 1
EXIT_CONN_FAIL = 2
