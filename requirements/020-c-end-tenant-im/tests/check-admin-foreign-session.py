#!/usr/bin/env python3
"""确认网关在转发前拒绝他人会话，且 admin 没有豁免。"""
from pathlib import Path
import sys

repo = Path(sys.argv[1])
proxy = (repo / "jasagent-gateway/src/main/java/dev/jasagent/gateway/proxy/StickyProxy.java").read_text(encoding="utf-8")
gate = (repo / "jasagent-gateway/src/main/java/dev/jasagent/gateway/proxy/ChatSessionGate.java").read_text(encoding="utf-8")
needle = "if (sessions != null && ChatSessionGate.targetsSession(path))"
start = proxy.find(needle)
if start < 0:
    raise SystemExit("StickyProxy 没有在转发前判断会话归属。")
chunk = proxy[start:start + 900]
if "ChatSessionGate.foreign(" not in chunk or 'json(HttpStatus.FORBIDDEN, "FORBIDDEN"' not in chunk:
    raise SystemExit("StickyProxy 没有在转发前对他人会话返回 403。")
for token in ("profile.admin()", "isAdmin(", "AccountRoles.ADMIN"):
    if token in chunk:
        raise SystemExit(f"会话拒绝路径给 admin 开了豁免：{token}")
if "boolean foreign(List<SessionLedger.Row> rows, String tenantId, String userId)" not in gate:
    raise SystemExit("ChatSessionGate.foreign 的判断带上了角色，或签名已改。")
print("admin 与普通用户走同一套转发前拒绝，没有角色豁免")
