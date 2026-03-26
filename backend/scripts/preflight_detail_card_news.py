#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]  # backend/
PROJECT = ROOT.parent


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except Exception as exc:
        raise RuntimeError(f"파일 읽기 실패: {path} ({exc})") from exc


def extract_call_blocks(text: str, fn_name: str) -> list[str]:
    blocks: list[str] = []
    start = 0
    while True:
        idx = text.find(fn_name, start)
        if idx < 0:
            break
        open_idx = text.find("(", idx)
        if open_idx < 0:
            break
        depth = 0
        i = open_idx
        while i < len(text):
            ch = text[i]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    blocks.append(text[idx : i + 1])
                    start = i + 1
                    break
            i += 1
        else:
            break
    return blocks


def main() -> int:
    errors: list[str] = []
    passes: list[str] = []

    rules_path = PROJECT / "server_rules" / "RULES.md"
    history_path = PROJECT / "server_rules" / "HISTORY_2026-02-10.md"
    stock_detail_path = PROJECT / "app" / "src" / "main" / "java" / "com" / "example" / "stock" / "ui" / "screens" / "StockDetailActivity.kt"
    chart_components_path = PROJECT / "app" / "src" / "main" / "java" / "com" / "example" / "stock" / "ui" / "common" / "ChartComponents.kt"
    screens_path = PROJECT / "app" / "src" / "main" / "java" / "com" / "example" / "stock" / "ui" / "screens" / "Screens.kt"
    backend_main_path = ROOT / "app" / "main.py"

    files = [
        rules_path,
        history_path,
        stock_detail_path,
        chart_components_path,
        screens_path,
        backend_main_path,
    ]
    for path in files:
        if not path.exists():
            errors.append(f"필수 파일 누락: {path}")

    if errors:
        print("[preflight] FAIL")
        for e in errors:
            print(f"- {e}")
        return 1

    rules = read_text(rules_path)
    history = read_text(history_path)
    stock_detail = read_text(stock_detail_path)
    chart_components = read_text(chart_components_path)
    screens = read_text(screens_path)
    backend_main = read_text(backend_main_path)

    if "## Detail Card News Regression Rules" not in rules:
        errors.append("RULES.md에 Detail Card News Regression Rules 섹션이 없습니다.")
    else:
        passes.append("RULES 회귀 섹션 확인")

    if "## Deep Retrospective (상세카드 커뮤니티/링크 이슈)" not in history:
        errors.append("HISTORY에 Deep Retrospective 항목이 없습니다.")
    else:
        passes.append("HISTORY 심층 회고 확인")

    if "## Non-Negotiable Checklist Going Forward" not in history:
        errors.append("HISTORY에 재발방지 체크리스트 항목이 없습니다.")
    else:
        passes.append("HISTORY 배포 차단 체크리스트 확인")

    community_blocks = [
        block
        for block in extract_call_blocks(stock_detail, "repo.getNewsArticles")
        if 'eventType = "community"' in block
    ]
    if not community_blocks:
        errors.append("StockDetailActivity: event_type=community 호출 블록을 찾지 못했습니다.")
    else:
        # Allow any ticker variable name (e.g., target, normalizedTicker), but disallow q(query) coupling.
        has_valid_block = any("ticker =" in b and "query =" not in b for b in community_blocks)
        if not has_valid_block:
            errors.append("StockDetailActivity: community 조회는 ticker 기반이며 q(query) 없이 호출되어야 합니다.")
        else:
            passes.append("커뮤니티 조회 축 분리(ticker + event_type)")

    for path, text in [
        (stock_detail_path, stock_detail),
        (chart_components_path, chart_components),
        (screens_path, screens),
    ]:
        if "digits.length >= 6 -> digits.takeLast(6)" not in text or "digits.isNotBlank() -> digits.padStart(6, '0')" not in text:
            errors.append(f"{path.name}: 네이버 링크 종목코드 6자리 정규화 로직이 없습니다.")
    if not any(path.name in e for e in errors):
        passes.append("네이버 링크 6자리 정규화(3개 화면) 확인")

    if "(?<=[.!?。])\\\\s+" not in stock_detail or "(?<=[.!?。])(?=[^\\\\s\\\\n])" not in stock_detail:
        errors.append("StockDetailActivity: 뉴스/커뮤니티 본문 줄바꿈 정규식이 누락되었습니다.")
    else:
        passes.append("본문 줄바꿈 포맷 정규식 확인")

    required_backend_tokens = [
        "needs_community_backfill",
        'event_type_i == "community"',
        "if ticker_i and (not rows or needs_community_backfill):",
        "include_naver_finance_community=allow_community_backfill",
    ]
    for token in required_backend_tokens:
        if token not in backend_main:
            errors.append(f"backend/app/main.py 누락 토큰: {token}")
    if not any("backend/app/main.py" in e for e in errors):
        passes.append("저건수 커뮤니티 backfill 보강 로직 확인")

    if errors:
        print("[preflight] FAIL")
        for e in errors:
            print(f"- {e}")
        return 1

    print("[preflight] PASS")
    for p in passes:
        print(f"- {p}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
