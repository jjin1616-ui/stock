#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]  # backend/
PROJECT = ROOT.parent


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except Exception as exc:  # pragma: no cover - preflight guard
        raise RuntimeError(f"파일 읽기 실패: {path} ({exc})") from exc


def extract_function(text: str, fn_name: str) -> str:
    pattern = re.compile(rf"^def\s+{re.escape(fn_name)}\s*\(", re.MULTILINE)
    m = pattern.search(text)
    if not m:
        return ""
    start = m.start()
    next_fn = re.compile(r"^def\s+\w+\s*\(", re.MULTILINE).search(text, m.end())
    end = next_fn.start() if next_fn else len(text)
    return text[start:end]


def main() -> int:
    errors: list[str] = []
    passes: list[str] = []

    backend_main_path = ROOT / "app" / "main.py"
    backend_schema_path = ROOT / "app" / "schemas.py"
    viewmodel_path = (
        PROJECT
        / "app"
        / "src"
        / "main"
        / "java"
        / "com"
        / "example"
        / "stock"
        / "viewmodel"
        / "ViewModels.kt"
    )
    ui_path = (
        PROJECT
        / "app"
        / "src"
        / "main"
        / "java"
        / "com"
        / "example"
        / "stock"
        / "ui"
        / "screens"
        / "AutoTradeScreen.kt"
    )
    rules_path = PROJECT / "server_rules" / "RULES.md"
    history_path = PROJECT / "server_rules" / "HISTORY_2026-02-26.md"

    for p in (backend_main_path, backend_schema_path, viewmodel_path, ui_path, rules_path, history_path):
        if not p.exists():
            errors.append(f"필수 파일 누락: {p}")
    if errors:
        print("[preflight-autotrade] FAIL")
        for e in errors:
            print(f"- {e}")
        return 1

    backend_main = read_text(backend_main_path)
    backend_schema = read_text(backend_schema_path)
    viewmodel = read_text(viewmodel_path)
    ui = read_text(ui_path)
    rules = read_text(rules_path)
    history = read_text(history_path)

    # 1) 라우트 존재 확인
    if '@app.post("/autotrade/orders/{order_id}/cancel"' not in backend_main:
        errors.append("주문 개별 취소 라우트 누락: /autotrade/orders/{order_id}/cancel")
    else:
        passes.append("개별 취소 라우트 확인")
    if '@app.post("/autotrade/orders/pending-cancel"' not in backend_main:
        errors.append("주문 일괄 취소 라우트 누락: /autotrade/orders/pending-cancel")
    else:
        passes.append("일괄 취소 라우트 확인")

    # 2) 계약: 취소 실패를 HTTP 400으로 던지지 않고 ok/message로 반환
    fn_body = extract_function(backend_main, "cancel_autotrade_order")
    if not fn_body:
        errors.append("cancel_autotrade_order 함수 본문 추출 실패")
    else:
        if "ok=bool(ok)" not in fn_body:
            errors.append("cancel_autotrade_order: 응답 ok=bool(ok) 계약이 없습니다.")
        if "raise HTTPException(status_code=400, detail=message)" in fn_body:
            errors.append("cancel_autotrade_order: 취소 실패를 HTTP 400으로 던지는 회귀가 감지되었습니다.")
        if 'return AutoTradeOrderCancelResponse(' not in fn_body:
            errors.append("cancel_autotrade_order: 표준 응답 반환이 없습니다.")
        if "ok=bool(ok)" in fn_body and "raise HTTPException(status_code=400, detail=message)" not in fn_body:
            passes.append("취소 실패 message 응답 계약 확인")

    # 3) 하위호환: pending-cancel environment에 paper 허용
    if 'class AutoTradePendingCancelRequest' not in backend_schema:
        errors.append("AutoTradePendingCancelRequest 스키마 누락")
    else:
        if 'environment: Literal["paper", "demo", "prod"] | None = None' not in backend_schema:
            errors.append("pending-cancel 환경 스키마에 paper 하위호환이 누락되었습니다.")
        else:
            passes.append("pending-cancel environment 하위호환 확인")

    # 4) 앱 메시지 계약: 에러 humanize + ok=false 스낵바
    if "import com.example.stock.data.repository.humanizeApiError" not in viewmodel:
        errors.append("ViewModels: humanizeApiError import 누락")
    if "humanizeApiError(this)" not in viewmodel:
        errors.append("ViewModels: 네트워크 에러를 humanizeApiError로 변환하지 않습니다.")
    else:
        passes.append("ViewModel 에러 메시지 변환 확인")

    if 'orderCancelState.data != null -> "접수취소 실패:' not in ui:
        errors.append("AutoTradeScreen: ok=false 취소 실패 스낵바 문구 누락")
    else:
        passes.append("AutoTradeScreen 실패 스낵바 분기 확인")

    # 5) 회고 동기화 확인
    if "자동매매 주문 취소 실패" not in rules:
        errors.append("RULES.md에 자동매매 취소 실패 메시지 규칙이 없습니다.")
    else:
        passes.append("RULES 취소 실패 규칙 확인")
    if "자동 화면 400 오류 해소" not in history:
        errors.append("HISTORY_2026-02-26.md 최신 회고 항목 누락(자동 화면 400 오류 해소).")
    else:
        passes.append("HISTORY 최신 회고 항목 확인")

    if errors:
        print("[preflight-autotrade] FAIL")
        for e in errors:
            print(f"- {e}")
        return 1

    print("[preflight-autotrade] PASS")
    for p in passes:
        print(f"- {p}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
