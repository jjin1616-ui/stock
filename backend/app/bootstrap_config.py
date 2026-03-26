from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any
from app.config import settings

ROOT = Path(__file__).resolve().parent
MANIFEST_PATH = ROOT / "bootstrap_profiles" / "manifest_seed.json"
STOCK_V2_APK_META_PATH = Path(settings.apk_dir) / "latest.stockv2.json"


def load_bootstrap_manifest() -> dict[str, Any]:
    return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))


def load_stock_v2_apk_meta() -> dict[str, Any]:
    if not STOCK_V2_APK_META_PATH.is_file():
        return {}
    try:
        return json.loads(STOCK_V2_APK_META_PATH.read_text(encoding="utf-8"))
    except Exception:
        return {}


def build_bootstrap_manifest(profile: str, base_url: str) -> dict[str, Any]:
    manifest = copy.deepcopy(load_bootstrap_manifest())
    latest_apk_meta = load_stock_v2_apk_meta()
    normalized_profile = profile.strip() or "dev_local"
    normalized_base = base_url.rstrip("/")

    manifest["id"] = f"{manifest['id']}-{normalized_profile}"
    manifest["appName"] = "stock_v2"
    manifest.setdefault("copyDictionary", {})["active_profile"] = normalized_profile
    manifest["copyDictionary"]["recommended_update_url"] = f"{normalized_base}/update?profile={normalized_profile}"
    latest_recommended_version = int(latest_apk_meta.get("version_code") or manifest.get("recommendedVersion") or 1)
    latest_min_supported_version = int(
        latest_apk_meta.get("min_supported_version_code") or manifest.get("minSupportedVersion") or 1
    )
    manifest["recommendedVersion"] = latest_recommended_version
    manifest["minSupportedVersion"] = latest_min_supported_version
    manifest["notices"] = [
        {
            "id": "remote_profile_notice",
            "text": f"backend bootstrap profile={normalized_profile} 응답입니다.",
            "level": "INFO",
        }
    ] + manifest.get("notices", [])

    premarket_tab = next((tab for tab in manifest.get("tabs", []) if tab.get("id") == "premarket"), None)
    if premarket_tab and premarket_tab.get("page", {}).get("sections"):
        premarket_tab["page"]["sections"][0].setdefault("props", {})["metric_2_label"] = "게이트"

    if normalized_profile == "maintenance_gate":
        manifest["maintenanceMode"] = True
        manifest["loadingPolicy"]["message"] = "점검 모드 응답을 테스트합니다."
    elif normalized_profile == "force_update_gate":
        manifest["minSupportedVersion"] = max(latest_recommended_version, latest_min_supported_version, 20099)
        manifest["loadingPolicy"]["message"] = "최소 지원 버전 게이트를 테스트합니다."
    elif normalized_profile in {"recommended_update", "dev_local"}:
        manifest["recommendedVersion"] = latest_recommended_version
        manifest["loadingPolicy"]["message"] = "권장 업데이트 배너를 테스트합니다."
    elif normalized_profile == "news_focus":
        manifest["initialTabId"] = "news"
        manifest["loadingPolicy"]["message"] = "뉴스 탭 우선 진입 remote 응답입니다."
    elif normalized_profile in {"premarket_focus", "daytrade_focus"}:
        manifest["initialTabId"] = "premarket"
        manifest["loadingPolicy"]["message"] = "단타 탭 우선 진입 remote 응답입니다."
    elif normalized_profile == "supply_focus":
        manifest["initialTabId"] = "supply"
        manifest["loadingPolicy"]["message"] = "수급 탭 우선 진입 remote 응답입니다."
    elif normalized_profile == "holdings_focus":
        manifest["initialTabId"] = "holdings"
        manifest["loadingPolicy"]["message"] = "보유 탭 우선 진입 remote 응답입니다."
    elif normalized_profile == "autotrade_focus":
        manifest["initialTabId"] = "autotrade"
        manifest["loadingPolicy"]["message"] = "자동 탭 우선 진입 remote 응답입니다."
    elif normalized_profile == "settings_focus":
        manifest["initialTabId"] = "settings"
        manifest["loadingPolicy"]["message"] = "설정 탭 우선 진입 remote 응답입니다."
    elif normalized_profile == "movers_focus":
        manifest["initialTabId"] = "movers"
        manifest["loadingPolicy"]["message"] = "급등 탭 우선 진입 remote 응답입니다."
    elif normalized_profile == "us_focus":
        manifest["initialTabId"] = "us"
        manifest["loadingPolicy"]["message"] = "미장 탭 우선 진입 remote 응답입니다."
    elif normalized_profile == "longterm_focus":
        manifest["initialTabId"] = "longterm"
        manifest["loadingPolicy"]["message"] = "장투 탭 우선 진입 remote 응답입니다."
    elif normalized_profile == "papers_focus":
        manifest["initialTabId"] = "papers"
        manifest["loadingPolicy"]["message"] = "논문 탭 우선 진입 remote 응답입니다."
    elif normalized_profile == "favorites_focus":
        manifest["initialTabId"] = "eod"
        manifest["loadingPolicy"]["message"] = "관심 탭 우선 진입 remote 응답입니다."
    elif normalized_profile == "alerts_focus":
        manifest["initialTabId"] = "alerts"
        manifest["loadingPolicy"]["message"] = "알림 탭 우선 진입 remote 응답입니다."
    else:
        manifest["loadingPolicy"]["message"] = "backend bootstrap API에서 manifest, 공지, feature flag를 내려줍니다."

    return manifest
