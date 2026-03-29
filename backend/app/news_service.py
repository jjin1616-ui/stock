from __future__ import annotations

import hashlib
import html
import json
import logging
import re
import uuid
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timedelta
from email.utils import parsedate_to_datetime
from queue import Queue
from threading import Lock, Thread
from typing import Any, Iterable
from urllib.parse import parse_qsl, quote, unquote, urlencode, urljoin, urlparse, urlunparse
from zoneinfo import ZoneInfo

import requests
try:
    from bs4 import BeautifulSoup
except Exception:  # pragma: no cover
    BeautifulSoup = None

from app.config import settings
from app.models import NewsArticle, NewsCluster, NewsEntityMention
from app.storage import session_scope
from engine.universe import load_universe
try:
    from engine.universe import _load_universe_krx_cache as load_universe_krx_cache
except Exception:  # pragma: no cover - fallback when private helper is unavailable
    load_universe_krx_cache = None

logger = logging.getLogger("stock.news")
SEOUL = ZoneInfo(settings.app_tz)


DEFAULT_RSS_FEEDS: list[tuple[str, str]] = [
    ("hankyung_all", "https://www.hankyung.com/feed/all-news"),
    ("hankyung_finance", "https://www.hankyung.com/feed/finance"),
    ("newsis_sokbo", "https://www.newsis.com/RSS/sokbo.xml"),
    ("newsis_economy", "https://www.newsis.com/RSS/economy.xml"),
    ("newsis_bank", "https://www.newsis.com/RSS/bank.xml"),
    ("sbs_rss", "https://news.sbs.co.kr/news/rss.do"),
    ("yna_market", "https://www.yna.co.kr/rss/market.xml"),
    ("yna_economy", "https://www.yna.co.kr/rss/economy.xml"),
    ("mk_stock", "https://www.mk.co.kr/rss/50200011/"),
    ("mk_economy", "https://www.mk.co.kr/rss/30100041/"),
    ("biz_chosun", "https://biz.chosun.com/arc/outboundfeeds/rss/?outputType=xml"),
]

RISK_EVENT_TYPES = {"offering", "regulation", "lawsuit"}
HIGH_SIGNAL_EVENT_TYPES = {"earnings", "contract", "buyback", "offering", "mna", "report"}

# Keep RSS articles focused on market/stock context.
_MARKET_KEYWORD_RE = re.compile(
    r"(주식|증시|코스피|코스닥|코스피200|코스닥150|상장|공시|실적|영업이익|매출|가이던스|"
    r"수주|공급계약|계약|자사주|자기주식|배당|유상증자|무상증자|전환사채|신주인수권|"
    r"인수|합병|m&a|목표가|투자의견|리포트|etf|ipo|시총|환율|금리|원달러|달러원|"
    r"외국인|기관|개인투자자|반도체|배터리|원전|조선|철강|정유|증권|은행|업황|수출|"
    r"내수|테마주|선물|옵션|지수)",
    flags=re.IGNORECASE,
)
_NOISE_KEYWORD_RE = re.compile(
    r"(음주운전|교통사고|폭행|살인|절도|강도|화재|산불|실종|사망|부상|연예|아이돌|드라마|"
    r"예능|영화|축구|야구|농구|배구|날씨|폭설|폭우|한파|폭염|경기결과)",
    flags=re.IGNORECASE,
)


def now_kst() -> datetime:
    # Store naive wall-time in APP_TZ (consistent with Access Control now()).
    return datetime.now(tz=SEOUL).replace(tzinfo=None)


def _to_kst_naive(dt: datetime) -> datetime:
    if dt.tzinfo is None:
        # Assume already in KST wall-time.
        return dt
    return dt.astimezone(SEOUL).replace(tzinfo=None)


def _published_ymd(dt: datetime) -> int:
    d = dt.date()
    return int(d.strftime("%Y%m%d"))


def _md5(s: str) -> str:
    return hashlib.md5(s.encode("utf-8")).hexdigest()


def _strip_html(s: str) -> str:
    raw = (s or "").strip()
    if not raw:
        return ""
    # crude but safe enough for MVP summaries
    raw = re.sub(r"<[^>]+>", " ", raw)
    raw = re.sub(r"\s+", " ", raw).strip()
    return raw


def _normalize_search_text(s: str) -> str:
    return re.sub(r"[^0-9A-Za-z가-힣]", "", str(s or "")).upper()


_NAME_WORD_CHARS = "0-9A-Za-z가-힣"
_NAME_PARTICLE_RE = (
    r"(?:은|는|이|가|을|를|와|과|의|에|도|로|으로|만|부터|까지|께서|에서|에게|한테)?"
)


def _contains_name_strict(text: str, name: str) -> bool:
    text_i = str(text or "")
    name_i = str(name or "").strip()
    if not text_i or len(name_i) < 3:
        return False
    pat = rf"(?<![{_NAME_WORD_CHARS}]){re.escape(name_i)}{_NAME_PARTICLE_RE}(?![{_NAME_WORD_CHARS}])"
    return bool(re.search(pat, text_i))


def _contains_name(text: str, name: str) -> bool:
    if _contains_name_strict(text, name):
        return True
    if not text or not name:
        return False
    # Fallback for spacing variants (e.g. "부광 약품"), while still requiring full-name length.
    n_text = _normalize_search_text(re.sub(r"\s+", "", str(text or "")))
    n_name = _normalize_search_text(re.sub(r"\s+", "", str(name or "")))
    return bool(len(n_name) >= 4 and n_name and n_name in n_text)


def _normalize_title_prefix(title: str, limit: int = 30) -> str:
    s = (title or "").strip()
    s = re.sub(r"\s+", " ", s)
    # keep hangul/latin/digits and a small punctuation set
    s = re.sub(r"[^0-9A-Za-z가-힣 .\\-_/]", "", s)
    s = s.lower().strip()
    return s[:limit]


def _normalize_title_full(title: str) -> str:
    """클러스터링용 전체 제목 정규화 (유사도 비교 대상)."""
    s = (title or "").strip()
    s = re.sub(r"\s+", " ", s)
    s = re.sub(r"[^0-9A-Za-z가-힣 ]", "", s)
    return s.lower().strip()


def _title_similarity(a: str, b: str) -> float:
    """자카드 유사도 기반 제목 비교 (외부 의존성 없음).

    공백 단위 토큰화 후 자카드 계수를 계산한다.
    짧은 제목은 bigram 기반으로 보완한다.
    """
    # 빈 제목이면 유사도 0 — 빈 문자열끼리 병합 방지
    if not (a or "").strip() or not (b or "").strip():
        return 0.0
    na, nb = _normalize_title_full(a), _normalize_title_full(b)
    if not na or not nb:
        return 0.0

    # 단어 단위 자카드
    tokens_a, tokens_b = set(na.split()), set(nb.split())
    if tokens_a and tokens_b:
        jaccard_word = len(tokens_a & tokens_b) / len(tokens_a | tokens_b)
    else:
        jaccard_word = 0.0

    # 문자 bigram 자카드 (짧은 제목 보완)
    def _bigrams(s: str) -> set[str]:
        s = s.replace(" ", "")
        return {s[i:i+2] for i in range(len(s) - 1)} if len(s) >= 2 else {s}

    bg_a, bg_b = _bigrams(na), _bigrams(nb)
    jaccard_bg = len(bg_a & bg_b) / len(bg_a | bg_b) if (bg_a and bg_b) else 0.0

    # 두 방식의 최대값 사용
    return max(jaccard_word, jaccard_bg)


# 유사도 임계값: 이 이상이면 같은 클러스터
_CLUSTER_SIMILARITY_THRESHOLD = 0.7

# 클러스터 대표 제목 → 클러스터 키 매핑 (인메모리 캐시, 날짜별)
_cluster_title_cache: dict[str, list[tuple[str, str]]] = {}  # group_key -> [(title, cluster_key)]
_cluster_cache_lock = Lock()


def compute_cluster_key(theme_key: str, event_type: str, published_ymd: int, title: str) -> str:
    """유사도 기반 클러스터 키 생성.

    동일 (theme, event, ymd) 그룹 내에서 기존 제목과 유사도 비교 후
    0.7 이상이면 같은 클러스터 키를 재사용한다.
    새 클러스터면 가장 긴 제목을 대표로 등록한다.
    """
    group_key = f"{theme_key}|{event_type}|{published_ymd}"

    with _cluster_cache_lock:
        titles = _cluster_title_cache.get(group_key, [])

        # 빈 제목이면 유사도 비교 건너뛰고 고유 키 생성
        if not (title or "").strip():
            empty_ckey = _md5(f"{group_key}|empty|{uuid.uuid4().hex}")
            titles.append(("", empty_ckey))
            _cluster_title_cache[group_key] = titles
            return empty_ckey

        # 기존 클러스터 중 유사한 제목 검색
        best_sim = 0.0
        best_ckey = None
        for existing_title, ckey in titles:
            sim = _title_similarity(title, existing_title)
            if sim > best_sim:
                best_sim = sim
                best_ckey = ckey

        if best_sim >= _CLUSTER_SIMILARITY_THRESHOLD and best_ckey is not None:
            # 기존 클러스터에 속함 — 대표 제목 갱신 (더 긴 제목으로)
            for i, (et, ck) in enumerate(titles):
                if ck == best_ckey and len(title) > len(et):
                    titles[i] = (title, best_ckey)
                    break
            return best_ckey

        # 새 클러스터 생성
        prefix = _normalize_title_prefix(title, limit=30)
        raw = f"{group_key}|{prefix}"
        new_ckey = _md5(raw)
        titles.append((title, new_ckey))
        _cluster_title_cache[group_key] = titles
        return new_ckey


# --- 범용 부정어 리스트: 이벤트 키워드 근처(10자 이내)에 등장하면 부정 ---
_NEGATION_WORDS = ["불가", "불발", "실패", "무산", "취소", "철회", "부진", "하락", "충격", "위기", "축소", "감소", "하향"]

# 분류 규칙: (event_type, regex, 기본 confidence, 우선순위 — 낮을수록 높음)
_EVENT_RULES: list[tuple[str, re.Pattern, float, int]] = [
    ("earnings",   re.compile(r"(실적|잠정|영업이익|매출|가이던스)"),                              0.85, 1),
    ("contract",   re.compile(r"(단일판매|공급계약|수주|계약)"),                                    0.80, 2),
    ("buyback",    re.compile(r"(자사주|자기주식|소각|매입|취득|처분)"),                             0.80, 3),
    ("offering",   re.compile(r"(유상증자|무상증자|CB|BW|전환사채|신주인수권)", re.IGNORECASE),      0.85, 4),
    ("mna",        re.compile(r"(인수|합병|분할|M&A|영업양수도)", re.IGNORECASE),                   0.80, 5),
    ("regulation", re.compile(r"(규제|제재|과징금|조사|검찰|금감원|금융감독원)"),                    0.75, 6),
    ("lawsuit",    re.compile(r"(소송|분쟁|피소)"),                                                 0.75, 7),
    ("report",     re.compile(r"(목표가|투자의견|리포트|커버리지)"),                                 0.70, 8),
]


def _has_negation(text: str, event_type: str) -> bool:
    """범용 부정어 감지 — 이벤트 키워드 근처(10자 이내)에 부정어가 있으면 True.

    모든 event_type에 대해 동일하게 동작한다.
    _EVENT_RULES에서 해당 event_type의 regex로 키워드 위치를 찾고,
    부정어가 키워드 앞뒤 10자 이내에 있으면 부정으로 판단한다.
    """
    if not text:
        return False

    # 해당 event_type의 키워드 패턴 찾기
    evt_pattern = None
    for evt, pat, _, _ in _EVENT_RULES:
        if evt == event_type:
            evt_pattern = pat
            break
    if evt_pattern is None:
        return False

    # 이벤트 키워드의 모든 매칭 위치 수집
    keyword_matches = list(evt_pattern.finditer(text))
    if not keyword_matches:
        return False

    # 부정어의 모든 출현 위치 수집
    neg_positions: list[tuple[int, int]] = []
    for neg_word in _NEGATION_WORDS:
        start = 0
        while True:
            idx = text.find(neg_word, start)
            if idx == -1:
                break
            neg_positions.append((idx, idx + len(neg_word)))
            start = idx + 1

    if not neg_positions:
        return False

    # 키워드와 부정어가 10자 이내에 있으면 부정
    proximity = 10
    for km in keyword_matches:
        kw_start, kw_end = km.start(), km.end()
        for neg_start, neg_end in neg_positions:
            # 부정어가 키워드 앞에 있는 경우: 부정어 끝 ~ 키워드 시작
            if neg_end <= kw_start and (kw_start - neg_end) <= proximity:
                return True
            # 부정어가 키워드 뒤에 있는 경우: 키워드 끝 ~ 부정어 시작
            if neg_start >= kw_end and (neg_start - kw_end) <= proximity:
                return True
            # 부정어와 키워드가 겹치는 경우
            if neg_start < kw_end and neg_end > kw_start:
                return True
    return False


def classify_event_type(text: str) -> str:
    """규칙 기반 이벤트 분류 — confidence score + 우선순위 + 부정어 처리."""
    t = (text or "").strip()
    if not t:
        return "misc"

    # 모든 매칭 규칙 수집
    matches: list[tuple[str, float, int]] = []  # (event_type, confidence, priority)
    for evt, pat, base_conf, priority in _EVENT_RULES:
        m = pat.search(t)
        if m:
            conf = base_conf
            # 부정어가 있으면 confidence 대폭 감소
            if _has_negation(t, evt):
                conf *= 0.3
            # 매칭 키워드가 제목 앞쪽에 위치하면 가중
            if m.start() < len(t) * 0.3:
                conf = min(1.0, conf * 1.1)
            matches.append((evt, conf, priority))

    if not matches:
        return "misc"

    # 우선순위(낮을수록 우선) → confidence(높을수록 우선) 순으로 정렬
    matches.sort(key=lambda x: (x[2], -x[1]))

    # confidence가 0.5 미만이면 신뢰도 부족 → misc
    best_evt, best_conf, _ = matches[0]
    if best_conf < 0.5:
        return "misc"
    return best_evt


def classify_theme_key(text: str) -> str:
    t = (text or "").strip()
    if not t:
        return "ETC"
    if re.search(r"(반도체|DRAM|HBM|파운드리|메모리)", t, flags=re.IGNORECASE):
        return "SEMICON"
    if re.search(r"(2차전지|이차전지|배터리|양극재|음극재)", t):
        return "BATTERY"
    if re.search(r"(AI|데이터센터|클라우드|GPU|서버)", t, flags=re.IGNORECASE):
        return "AI_DC"
    if re.search(r"(바이오|제약|임상|FDA)", t, flags=re.IGNORECASE):
        return "BIO"
    if re.search(r"(방산|무기|K-방산)", t, flags=re.IGNORECASE):
        return "DEFENSE"
    if re.search(r"(원전|전력|변압기|송전)", t):
        return "POWER"
    if re.search(r"(자동차|전기차|부품)", t):
        return "AUTO"
    if re.search(r"(조선|해운)", t):
        return "SHIP"
    if re.search(r"(화학|정유|철강|소재)", t):
        return "CHEM"
    if re.search(r"(게임|콘텐츠)", t):
        return "GAME"
    if re.search(r"(은행|증권|보험)", t):
        return "FIN"
    if re.search(r"(플랫폼|인터넷|커머스)", t):
        return "PLATFORM"
    if re.search(r"(중국|환율|수출)", t):
        return "CHINA_FX"
    if re.search(r"(정책|규제|공매도|지원금)", t):
        return "POLICY"
    return "ETC"


def classify_polarity(event_type: str) -> str:
    if event_type in ("offering", "regulation", "lawsuit"):
        return "neg"
    if event_type in ("buyback", "contract"):
        return "pos"
    if event_type in ("earnings", "mna"):
        return "mixed"
    return "neutral"


def classify_impact(event_type: str) -> int:
    if event_type in ("earnings", "contract", "buyback", "mna"):
        return 80
    if event_type in ("offering", "regulation", "lawsuit"):
        return 80
    if event_type == "report":
        return 40
    return 20


@dataclass(frozen=True)
class NormalizedArticle:
    source: str
    source_uid: str
    url: str
    title: str
    summary: str | None
    published_at: datetime
    published_ymd: int
    event_type: str
    polarity: str
    impact: int
    theme_key: str
    tickers: list[str]


def _parse_rss_feeds_config() -> list[tuple[str, str]]:
    raw = (settings.news_rss_feeds or "").strip()
    if not raw:
        return DEFAULT_RSS_FEEDS
    try:
        data = json.loads(raw)
    except Exception:
        logger.exception("NEWS_RSS_FEEDS parse failed; falling back to defaults")
        return DEFAULT_RSS_FEEDS

    feeds: list[tuple[str, str]] = []
    if isinstance(data, list):
        for i, item in enumerate(data):
            if isinstance(item, str):
                url = item.strip()
                if url:
                    feeds.append((f"rss_{i+1}", url))
                continue
            if isinstance(item, dict):
                fid = str(item.get("id") or item.get("source") or f"rss_{i+1}").strip()
                url = str(item.get("url") or item.get("href") or "").strip()
                if fid and url:
                    feeds.append((fid, url))
                continue
    return feeds or DEFAULT_RSS_FEEDS


_MASTER_LOCK = Lock()
_MASTER_TICKERS: set[str] | None = None
_MASTER_NAMES: list[tuple[str, str]] | None = None  # (name, ticker), sorted longest-first
_MASTER_REFRESHED_AT: datetime | None = None


def _load_ticker_master(force: bool = False) -> tuple[set[str], list[tuple[str, str]]]:
    global _MASTER_TICKERS, _MASTER_NAMES, _MASTER_REFRESHED_AT
    with _MASTER_LOCK:
        if not force and _MASTER_TICKERS is not None and _MASTER_NAMES is not None:
            # refresh daily at most
            if _MASTER_REFRESHED_AT and (now_kst() - _MASTER_REFRESHED_AT) < timedelta(hours=24):
                return _MASTER_TICKERS, _MASTER_NAMES
        try:
            # Fast path for request-time extraction: prefer local cache snapshot and avoid
            # slow external listing calls (FDR/pykrx) on user-facing API latency path.
            df = None
            if callable(load_universe_krx_cache):
                try:
                    df_cache = load_universe_krx_cache()
                    if df_cache is not None and not df_cache.empty:
                        df = df_cache
                except Exception:
                    logger.exception("ticker master cache load failed; fallback to load_universe")
            if df is None:
                df = load_universe()
            tickers = set()
            pairs: list[tuple[str, str]] = []
            if df is not None and not df.empty:
                for _, row in df.iterrows():
                    code = str(row.get("Code") or "").strip()
                    name = str(row.get("Name") or "").strip()
                    if not re.fullmatch(r"\d{6}", code):
                        continue
                    if not name:
                        continue
                    tickers.add(code)
                    pairs.append((name, code))
            # Prefer longer names to reduce false positives.
            pairs.sort(key=lambda x: len(x[0]), reverse=True)
            _MASTER_TICKERS = tickers
            _MASTER_NAMES = pairs
            _MASTER_REFRESHED_AT = now_kst()
            return tickers, pairs
        except Exception:
            logger.exception("ticker master load failed; using empty mapping")
            _MASTER_TICKERS = set()
            _MASTER_NAMES = []
            _MASTER_REFRESHED_AT = now_kst()
            return _MASTER_TICKERS, _MASTER_NAMES


def _extract_tickers_from_text(title: str, summary: str | None) -> list[str]:
    tickers, names = _load_ticker_master()
    found: list[str] = []
    seen: set[str] = set()

    text_title = (title or "").strip()
    text_summary = (summary or "").strip()

    # 1) explicit numeric tickers
    for m in re.findall(r"\b(\d{6})\b", f"{text_title} {text_summary}"):
        if m in tickers and m not in seen:
            seen.add(m)
            found.append(m)

    # 2) name matching (title-first, then summary) with a length threshold.
    #    Apply both exact and normalized matching so variants like
    #    "부광 약품", "(주)부광약품" are still recognized.
    def _norm_ko(s: str) -> str:
        return re.sub(r"[^0-9A-Za-z가-힣]", "", (s or "")).upper()

    def _scan(text: str) -> None:
        if not text:
            return
        for name, code in names:
            if code in seen:
                continue
            if len(name) < 3:
                continue
            name_norm = _norm_ko(name)
            if not name_norm or len(name_norm) < 3:
                continue
            if _contains_name(text, name):
                seen.add(code)
                found.append(code)
            if len(found) >= 10:
                return

    if len(found) < 10:
        _scan(text_title)
    if len(found) < 10:
        _scan(text_summary)

    return found


def ticker_primary_name(ticker: str) -> str | None:
    tk = str(ticker or "").strip()
    if not re.fullmatch(r"\d{6}", tk):
        return None
    _, names = _load_ticker_master()
    for name, code in names:
        if code == tk and len(name) >= 3:
            return name
    return None


def _is_market_relevant_rss(
    *,
    title: str,
    summary: str | None,
    event_type: str,
    theme_key: str,
    tickers: list[str],
) -> bool:
    combined = f"{title} {summary or ''}".strip()
    if not combined:
        return False
    has_market_keyword = bool(_MARKET_KEYWORD_RE.search(combined))
    has_noise_keyword = bool(_NOISE_KEYWORD_RE.search(combined))

    score = 0
    if tickers:
        score += 4
    if event_type in HIGH_SIGNAL_EVENT_TYPES:
        score += 2
    if has_market_keyword:
        score += 2
    if theme_key != "ETC":
        score += 1

    # Generic ETC pieces should need stronger evidence.
    if theme_key == "ETC":
        score -= 1
    # Regulation/lawsuit without market vocabulary is usually social news noise.
    if event_type in {"regulation", "lawsuit"} and not has_market_keyword:
        score -= 2
    if has_noise_keyword:
        score -= 3

    return score >= 2


def _parse_xml_text(node: ET.Element | None) -> str:
    if node is None or node.text is None:
        return ""
    return str(node.text or "").strip()


def _parse_rss_or_atom(xml_bytes: bytes) -> list[dict[str, Any]]:
    """Parse RSS/Atom XML into a list of raw items.

    Returns list of raw items: {title, link, guid, summary, published_at?}

    Note: parsing failures are handled by the caller so we can attach feed id/url
    in meta.message without spamming stack traces.
    """
    root = ET.fromstring(xml_bytes)

    tag = (root.tag or "").lower()
    # Handle namespaces (e.g. {http://www.w3.org/2005/Atom}feed)
    if "feed" in tag:
        # Atom
        out: list[dict[str, Any]] = []
        ns_prefix = ""
        if root.tag.startswith("{"):
            ns_prefix = root.tag.split("}", 1)[0] + "}"
        for entry in root.findall(f"{ns_prefix}entry"):
            title = _parse_xml_text(entry.find(f"{ns_prefix}title"))
            link = ""
            for ln in entry.findall(f"{ns_prefix}link"):
                href = ln.attrib.get("href", "").strip()
                if href:
                    link = href
                    break
            guid = _parse_xml_text(entry.find(f"{ns_prefix}id"))
            summary = _parse_xml_text(entry.find(f"{ns_prefix}summary")) or _parse_xml_text(entry.find(f"{ns_prefix}content"))
            published_raw = _parse_xml_text(entry.find(f"{ns_prefix}published")) or _parse_xml_text(entry.find(f"{ns_prefix}updated"))
            published_at: datetime | None = None
            if published_raw:
                try:
                    published_at = datetime.fromisoformat(published_raw.replace("Z", "+00:00"))
                except Exception:
                    published_at = None
            out.append(
                {
                    "title": title,
                    "link": link,
                    "guid": guid,
                    "summary": summary,
                    "published_at": published_at,
                }
            )
        return out

    # RSS
    out: list[dict[str, Any]] = []
    channel = root.find("channel")
    items = channel.findall("item") if channel is not None else root.findall(".//item")
    for it in items:
        title = _parse_xml_text(it.find("title"))
        link = _parse_xml_text(it.find("link"))
        guid = _parse_xml_text(it.find("guid"))
        summary = _parse_xml_text(it.find("description"))
        pub_raw = _parse_xml_text(it.find("pubDate"))
        published_at: datetime | None = None
        if pub_raw:
            try:
                published_at = parsedate_to_datetime(pub_raw)
            except Exception:
                published_at = None
        out.append(
            {
                "title": title,
                "link": link,
                "guid": guid,
                "summary": summary,
                "published_at": published_at,
            }
        )
    return out


def _normalize_article_url(raw_url: str) -> str:
    u = html.unescape(str(raw_url or "").strip())
    if not u:
        return ""
    try:
        p = urlparse(u)
        if p.scheme not in ("http", "https") or not p.netloc:
            return ""
        kept_q = []
        for k, v in parse_qsl(p.query, keep_blank_values=False):
            lk = str(k or "").lower()
            if lk.startswith("utm_"):
                continue
            if lk in {"fbclid", "gclid", "cmpid"}:
                continue
            kept_q.append((k, v))
        q = urlencode(kept_q, doseq=True)
        p2 = p._replace(query=q, fragment="")
        return urlunparse(p2)
    except Exception:
        return u


def _published_at_from_url_or_now(article_url: str) -> datetime:
    m = re.search(r"/(20\d{2})/(\d{2})/(\d{2})/", str(article_url or ""))
    if not m:
        return now_kst()
    try:
        y = int(m.group(1))
        mo = int(m.group(2))
        d = int(m.group(3))
        base = now_kst()
        return datetime(y, mo, d, base.hour, base.minute, base.second)
    except Exception:
        return now_kst()


def fetch_naver_news_search(
    *,
    stock_name: str,
    stock_ticker: str,
    limit: int = 30,
    page_starts: list[int] | None = None,
    timeout_sec: int = 12,
) -> tuple[list[NormalizedArticle], list[str]]:
    errors: list[str] = []
    out: list[NormalizedArticle] = []
    name = str(stock_name or "").strip()
    ticker = str(stock_ticker or "").strip()
    if not name or not re.fullmatch(r"\d{6}", ticker):
        return out, ["naver_search: invalid_stock_input"]
    headers = {
        "User-Agent": "Mozilla/5.0 (compatible; KoreaStockDash/1.0; +https://search.naver.com)",
    }
    starts = list(page_starts or [1, 11, 21])
    seen_urls: set[str] = set()
    lim = max(1, min(int(limit), 90))

    req_timeout = max(2, min(int(timeout_sec), 20))
    for start in starts:
        if len(out) >= lim:
            break
        q = quote(name)
        page_url = (
            "https://search.naver.com/search.naver"
            f"?ssc=tab.news.all&where=news&sm=tab_jum&query={q}&start={start}"
        )
        try:
            resp = requests.get(page_url, headers=headers, timeout=req_timeout)
            if resp.status_code != 200:
                errors.append(f"naver_search: HTTP {resp.status_code} (start={start})")
                continue
            page = resp.text
        except Exception as e:
            errors.append(f"naver_search: fetch failed: {type(e).__name__} (start={start})")
            continue

        title_map: dict[str, str] = {}
        summary_map: dict[str, str] = {}
        if BeautifulSoup is None:
            errors.append("naver_search: bs4_missing")
            break
        try:
            soup = BeautifulSoup(page, "html.parser")
            for a in soup.select('a[data-heatmap-target=".tit"]'):
                href = _normalize_article_url(unquote(str(a.get("href") or "").strip()))
                if not href:
                    continue
                title = _strip_html(html.unescape(a.get_text(" ", strip=True)))
                if not title:
                    continue
                title_map[href] = title
            for a in soup.select('a[data-heatmap-target=".body"]'):
                href = _normalize_article_url(unquote(str(a.get("href") or "").strip()))
                if not href:
                    continue
                body = _strip_html(html.unescape(a.get_text(" ", strip=True)))
                if body:
                    summary_map[href] = body
        except Exception as e:
            errors.append(f"naver_search: parse_failed:{type(e).__name__} (start={start})")
            continue

        for href, title in title_map.items():
            if len(out) >= lim:
                break
            if href in seen_urls:
                continue
            summary = summary_map.get(href)
            combined = f"{title} {summary or ''}".strip()
            if not _contains_name(combined, name):
                continue
            seen_urls.add(href)
            event_type = classify_event_type(combined)
            polarity = classify_polarity(event_type)
            impact = classify_impact(event_type)
            theme_key = classify_theme_key(combined)
            # Ticker-targeted supplement path: force the target ticker to avoid
            # expensive whole-universe extraction on user request path.
            tickers = [ticker]
            host = (urlparse(href).netloc or "unknown").lower().replace("www.", "")
            source = f"naver_search_{host[:48]}"
            published_at = _published_at_from_url_or_now(href)
            out.append(
                NormalizedArticle(
                    source=source,
                    source_uid=_md5(href),
                    url=href,
                    title=title,
                    summary=summary or None,
                    published_at=published_at,
                    published_ymd=_published_ymd(published_at),
                    event_type=event_type,
                    polarity=polarity,
                    impact=impact,
                    theme_key=theme_key,
                    tickers=tickers,
                )
            )
    if out:
        errors.append(f"naver_search: collected={len(out)}")
    return out, errors


def _extract_naver_discussion_result(payload: dict[str, Any] | None) -> dict[str, Any] | None:
    if not isinstance(payload, dict):
        return None
    page_props = payload.get("props", {}).get("pageProps", {})
    dehydrated = page_props.get("dehydratedState", {})
    queries = dehydrated.get("queries", [])
    if not isinstance(queries, list):
        return None
    for q in queries:
        state = (q or {}).get("state", {})
        data = state.get("data", {})
        result = data.get("result") if isinstance(data, dict) else None
        if not isinstance(result, dict):
            continue
        if result.get("contentHtml") or result.get("contentJsonSwReplaced"):
            return result
    return None


def _parse_naver_discussion_written_at(text: str) -> datetime | None:
    raw = str(text or "").strip()
    if not raw:
        return None
    m = re.search(r"(20\d{2})\.(\d{2})\.(\d{2})\s+(\d{2}):(\d{2})", raw)
    if not m:
        return None
    try:
        return datetime(
            int(m.group(1)),
            int(m.group(2)),
            int(m.group(3)),
            int(m.group(4)),
            int(m.group(5)),
            0,
        )
    except Exception:
        return None


def fetch_naver_finance_community(
    *,
    stock_name: str,
    stock_ticker: str,
    limit: int = 24,
    pages: int = 2,
    timeout_sec: int = 6,
) -> tuple[list[NormalizedArticle], list[str]]:
    errors: list[str] = []
    out: list[NormalizedArticle] = []
    name = str(stock_name or "").strip()
    ticker = str(stock_ticker or "").strip()
    if not name or not re.fullmatch(r"\d{6}", ticker):
        return out, ["naver_finance_community: invalid_stock_input"]
    if BeautifulSoup is None:
        return out, ["naver_finance_community: bs4_missing"]

    lim = max(1, min(int(limit), 60))
    page_count = max(1, min(int(pages), 5))
    req_timeout = max(2, min(int(timeout_sec), 20))
    headers = {"User-Agent": "Mozilla/5.0 (compatible; KoreaStockDash/1.0; +https://finance.naver.com)"}
    seen_uid: set[str] = set()

    for page in range(1, page_count + 1):
        if len(out) >= lim:
            break
        list_url = f"https://finance.naver.com/item/board.naver?code={ticker}&page={page}"
        try:
            resp = requests.get(list_url, headers=headers, timeout=req_timeout)
            if resp.status_code != 200:
                errors.append(f"naver_finance_community: HTTP {resp.status_code} (page={page})")
                continue
            soup = BeautifulSoup(resp.text, "html.parser")
        except Exception as e:
            errors.append(f"naver_finance_community: list_fetch_failed:{type(e).__name__} (page={page})")
            continue

        anchors = soup.select('table.type2 a[href*="board_read.naver"]')
        if not anchors:
            continue

        for a in anchors:
            if len(out) >= lim:
                break
            title = _strip_html(html.unescape(a.get_text(" ", strip=True)))
            href_raw = unquote(str(a.get("href") or "").strip())
            if not title or not href_raw:
                continue
            read_url = _normalize_article_url(urljoin("https://finance.naver.com", href_raw))
            if not read_url:
                continue

            nid_match = re.search(r"[?&]nid=(\d+)", read_url)
            nid = nid_match.group(1) if nid_match else _md5(read_url)[:16]
            source_uid = f"{ticker}:{nid}"
            if source_uid in seen_uid:
                continue
            seen_uid.add(source_uid)

            published_at = now_kst()
            summary_text: str | None = None
            title_from_detail: str | None = None
            try:
                rr = requests.get(read_url, headers=headers, timeout=req_timeout)
                if rr.status_code == 200:
                    read_soup = BeautifulSoup(rr.text, "html.parser")
                    date_node = read_soup.select_one("table.view th.gray03")
                    if date_node is not None:
                        dt = _parse_naver_discussion_written_at(date_node.get_text(" ", strip=True))
                        if dt is not None:
                            published_at = dt

                    iframe = read_soup.select_one("iframe#contents")
                    iframe_src = str(iframe.get("src") or "").strip() if iframe is not None else ""
                    if iframe_src:
                        detail_url = _normalize_article_url(urljoin(read_url, iframe_src))
                        if detail_url:
                            dr = requests.get(detail_url, headers=headers, timeout=req_timeout)
                            if dr.status_code == 200:
                                m = re.search(
                                    r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>',
                                    dr.text,
                                    flags=re.S,
                                )
                                if m:
                                    payload = json.loads(m.group(1))
                                    result = _extract_naver_discussion_result(payload)
                                    if isinstance(result, dict):
                                        title_from_detail = str(result.get("title") or "").strip() or None
                                        dt = _parse_naver_discussion_written_at(str(result.get("writtenAt") or ""))
                                        if dt is not None:
                                            published_at = dt
                                        content_html = str(result.get("contentHtml") or "").strip()
                                        if content_html:
                                            summary_text = _strip_html(content_html)
            except Exception as e:
                errors.append(f"naver_finance_community: detail_parse_failed:{type(e).__name__} (nid={nid})")

            effective_title = title_from_detail or title
            combined = f"{effective_title} {summary_text or ''}".strip()
            # Ignore obvious cross-topic noise posts in the board list.
            if _NOISE_KEYWORD_RE.search(combined):
                continue

            out.append(
                NormalizedArticle(
                    source="naver_finance_community",
                    source_uid=source_uid,
                    url=read_url,
                    title=effective_title,
                    summary=summary_text or None,
                    published_at=published_at,
                    published_ymd=_published_ymd(published_at),
                    event_type="community",
                    polarity="mixed",
                    impact=25,
                    theme_key=classify_theme_key(combined),
                    tickers=[ticker],
                )
            )

    if out:
        errors.append(f"naver_finance_community: collected={len(out)}")
    return out, errors


def fetch_rss_feed(
    feed_id: str,
    url: str,
    *,
    limit: int,
    focus_name: str | None = None,
    focus_ticker: str | None = None,
    bypass_relevance_on_focus: bool = False,
    timeout_sec: int = 12,
) -> tuple[list[NormalizedArticle], list[str]]:
    errors: list[str] = []
    headers = {"User-Agent": "KoreaStockDash/1.0 (news fetch)"}
    try:
        req_timeout = max(2, min(int(timeout_sec), 20))
        resp = requests.get(url, headers=headers, timeout=req_timeout)
        if resp.status_code != 200:
            errors.append(f"{feed_id}: HTTP {resp.status_code}")
            return [], errors
        # Guard against non-feed HTML (common for index pages).
        head = (resp.content or b"")[:256].lstrip()
        head_l = head.lower()
        ct = str(resp.headers.get("content-type") or "").lower()
        if "html" in ct or head_l.startswith(b"<!doctype") or b"<html" in head_l[:80]:
            errors.append(f"{feed_id}: not_rss_xml")
            return [], errors
        try:
            raw_items = _parse_rss_or_atom(resp.content)
        except Exception:
            errors.append(f"{feed_id}: parse_failed")
            return [], errors
    except Exception as e:
        errors.append(f"{feed_id}: fetch failed: {type(e).__name__}")
        logger.exception("rss fetch failed: %s", feed_id)
        return [], errors

    out: list[NormalizedArticle] = []
    skipped_non_market = 0
    focus_hit_count = 0
    now_ts = now_kst()
    focus_name_i = str(focus_name or "").strip()
    focus_ticker_i = str(focus_ticker or "").strip()
    focus_mode = bool(focus_name_i and bypass_relevance_on_focus)
    for raw in raw_items[: max(0, limit)]:
        title = str(raw.get("title") or "").strip()
        link = str(raw.get("link") or "").strip()
        if not title or not link:
            continue
        guid = str(raw.get("guid") or "").strip()
        source_uid = guid if guid else _md5(link)
        summary = _strip_html(str(raw.get("summary") or ""))
        published_at = raw.get("published_at")
        if isinstance(published_at, datetime):
            published_at_norm = _to_kst_naive(published_at)
        else:
            published_at_norm = now_ts
            errors.append(f"{feed_id}: published_at missing ({source_uid})")
        combined = f"{title} {summary}".strip()
        event_type = classify_event_type(combined)
        polarity = classify_polarity(event_type)
        impact = classify_impact(event_type)
        theme_key = classify_theme_key(combined)
        focus_hit = False
        if focus_name_i:
            focus_hit = _contains_name(combined, focus_name_i)
        if focus_mode and (not focus_hit):
            continue
        if focus_hit:
            focus_hit_count += 1
        if focus_mode:
            tickers = [focus_ticker_i] if re.fullmatch(r"\d{6}", focus_ticker_i) else []
        else:
            tickers = _extract_tickers_from_text(title, summary)
            if focus_hit and focus_ticker_i and re.fullmatch(r"\d{6}", focus_ticker_i):
                if focus_ticker_i not in tickers:
                    tickers.append(focus_ticker_i)
        if settings.news_rss_market_relevance_filter and not _is_market_relevant_rss(
            title=title,
            summary=summary,
            event_type=event_type,
            theme_key=theme_key,
            tickers=tickers,
        ):
            if not (bypass_relevance_on_focus and focus_hit):
                skipped_non_market += 1
                continue
        out.append(
            NormalizedArticle(
                source=feed_id,
                source_uid=source_uid,
                url=link,
                title=title,
                summary=summary or None,
                published_at=published_at_norm,
                published_ymd=_published_ymd(published_at_norm),
                event_type=event_type,
                polarity=polarity,
                impact=impact,
                theme_key=theme_key,
                tickers=tickers,
            )
        )
    if skipped_non_market > 0:
        errors.append(f"{feed_id}: non_market_skipped={skipped_non_market}")
    if focus_name_i:
        errors.append(f"{feed_id}: focus_hits={focus_hit_count}")
    return out, errors


def fetch_dart(*, limit: int) -> tuple[list[NormalizedArticle], list[str]]:
    errors: list[str] = []
    key = (settings.opendart_api_key or "").strip()
    if not key:
        errors.append("dart: OPENDART_API_KEY missing")
        logger.error("OpenDART disabled: OPENDART_API_KEY missing")
        return [], errors

    now_ts = now_kst()
    ymd = now_ts.strftime("%Y%m%d")
    url = "https://opendart.fss.or.kr/api/list.json"
    params = {
        "crtfc_key": key,
        "bgn_de": ymd,
        "end_de": ymd,
        "page_no": 1,
        "page_count": min(max(1, limit), 100),
    }
    try:
        resp = requests.get(url, params=params, timeout=12)
        data = resp.json()
    except Exception as e:
        errors.append(f"dart: fetch failed: {type(e).__name__}")
        logger.exception("dart fetch failed")
        return [], errors

    if str(data.get("status")) != "000":
        errors.append(f"dart: api status={data.get('status')} msg={data.get('message')}")
        logger.error("dart api error: %s", data)
        return [], errors

    tickers_set, _ = _load_ticker_master()

    out: list[NormalizedArticle] = []
    items = data.get("list") or []
    for it in items[: max(0, limit)]:
        try:
            corp_name = str(it.get("corp_name") or "").strip()
            stock_code = str(it.get("stock_code") or "").strip()
            report_nm = str(it.get("report_nm") or "").strip()
            rcept_no = str(it.get("rcept_no") or "").strip()
            rcept_dt = str(it.get("rcept_dt") or "").strip()
            if not rcept_no or not report_nm:
                continue
            title = f"{corp_name} {report_nm}".strip()
            doc_url = f"https://dart.fss.or.kr/dsaf001/main.do?rcpNo={rcept_no}"

            # Use DART's date if present; time is not provided in list.json, so keep ingestion time.
            pub = now_ts
            if re.fullmatch(r"\d{8}", rcept_dt):
                try:
                    d = datetime.strptime(rcept_dt, "%Y%m%d").date()
                    pub = datetime.combine(d, now_ts.time())
                except Exception:
                    pub = now_ts

            event_type = classify_event_type(report_nm)
            polarity = classify_polarity(event_type)
            impact = classify_impact(event_type)
            theme_key = "ETC"
            tickers = []
            if re.fullmatch(r"\d{6}", stock_code) and stock_code in tickers_set:
                tickers = [stock_code]
            out.append(
                NormalizedArticle(
                    source="dart",
                    source_uid=rcept_no,
                    url=doc_url,
                    title=title,
                    summary=None,
                    published_at=pub,
                    published_ymd=_published_ymd(pub),
                    event_type=event_type,
                    polarity=polarity,
                    impact=impact,
                    theme_key=theme_key,
                    tickers=tickers,
                )
            )
        except Exception:
            logger.exception("dart item parse failed")
    return out, errors


@dataclass
class IngestStats:
    inserted: int = 0
    updated: int = 0
    clusters_updated: int = 0
    mentions_inserted: int = 0


def _upsert_mentions(
    session,
    *,
    article_id: int,
    cluster_id: int | None,
    tickers: Iterable[str],
    published_at: datetime,
    published_ymd: int,
    event_type: str,
    impact: int,
) -> int:
    inserted = 0
    for t in tickers:
        ticker = str(t or "").strip().upper()
        if not ticker:
            continue
        row = session.query(NewsEntityMention).filter(NewsEntityMention.ticker == ticker, NewsEntityMention.article_id == article_id).first()
        if row is None:
            session.add(
                NewsEntityMention(
                    ticker=ticker,
                    article_id=article_id,
                    cluster_id=cluster_id,
                    mention_weight=1.0,
                    published_at=published_at,
                    published_ymd=published_ymd,
                    event_type=event_type,
                    impact=impact,
                )
            )
            inserted += 1
        else:
            # Keep denormalized attributes consistent.
            row.cluster_id = cluster_id
            row.published_at = published_at
            row.published_ymd = published_ymd
            row.event_type = event_type
            row.impact = impact
    return inserted


def upsert_articles(articles: list[NormalizedArticle]) -> tuple[IngestStats, list[str]]:
    stats = IngestStats()
    messages: list[str] = []
    if not articles:
        return stats, messages

    # Deduplicate within batch.
    seen_keys: set[tuple[str, str]] = set()
    deduped: list[NormalizedArticle] = []
    for a in articles:
        k = (a.source, a.source_uid)
        if k in seen_keys:
            continue
        seen_keys.add(k)
        deduped.append(a)

    now_ts = now_kst()
    touched_cluster_keys: set[str] = set()
    article_rows: list[tuple[NewsArticle, str, list[str]]] = []  # (row, cluster_key, tickers)

    try:
        with session_scope() as session:
            for a in deduped:
                if not a.source or not a.source_uid or not a.url or not a.title:
                    continue
                tickers_json = json.dumps(a.tickers or [], ensure_ascii=False) if (a.tickers is not None) else None
                existing = (
                    session.query(NewsArticle)
                    .filter(NewsArticle.source == a.source, NewsArticle.source_uid == a.source_uid)
                    .first()
                )
                if existing is None:
                    row = NewsArticle(
                        source=a.source,
                        source_uid=a.source_uid,
                        url=a.url,
                        title=a.title,
                        summary=a.summary,
                        published_at=a.published_at,
                        ingested_at=now_ts,
                        published_ymd=a.published_ymd,
                        event_type=a.event_type,
                        polarity=a.polarity,
                        impact=int(a.impact),
                        theme_key=a.theme_key,
                        tickers_json=tickers_json,
                    )
                    session.add(row)
                    stats.inserted += 1
                else:
                    row = existing
                    row.url = a.url
                    row.title = a.title
                    row.summary = a.summary
                    row.published_at = a.published_at
                    row.ingested_at = now_ts
                    row.published_ymd = a.published_ymd
                    row.event_type = a.event_type
                    row.polarity = a.polarity
                    row.impact = int(a.impact)
                    row.theme_key = a.theme_key
                    row.tickers_json = tickers_json
                    stats.updated += 1

                ckey = compute_cluster_key(a.theme_key, a.event_type, a.published_ymd, a.title)
                touched_cluster_keys.add(ckey)
                article_rows.append((row, ckey, list(a.tickers or [])))

            session.flush()

            # Upsert clusters deterministically based on current DB state for each touched key.
            cluster_id_map: dict[str, int] = {}
            for ckey in touched_cluster_keys:
                # find a representative row for this cluster key (from current batch)
                rep = next((r for (r, k, _) in article_rows if k == ckey), None)
                if rep is None:
                    continue
                theme_key = rep.theme_key
                event_type = rep.event_type
                published_ymd = rep.published_ymd
                # candidates are constrained by (theme,event,ymd)
                candidates = (
                    session.query(NewsArticle)
                    .filter(
                        NewsArticle.theme_key == theme_key,
                        NewsArticle.event_type == event_type,
                        NewsArticle.published_ymd == published_ymd,
                    )
                    .all()
                )
                members: list[NewsArticle] = []
                for ar in candidates:
                    if compute_cluster_key(ar.theme_key, ar.event_type, ar.published_ymd, ar.title) == ckey:
                        members.append(ar)
                if not members:
                    continue

                start = min(m.published_at for m in members)
                end = max(m.published_at for m in members)
                title = members[0].title
                summary = next((m.summary for m in members if (m.summary or "").strip()), None)

                freq: dict[str, int] = {}
                for m in members:
                    try:
                        ts = json.loads(m.tickers_json or "[]")
                        if isinstance(ts, list):
                            for t in ts:
                                tt = str(t or "").strip().upper()
                                if tt:
                                    freq[tt] = freq.get(tt, 0) + 1
                    except Exception:
                        continue
                top_tickers = [t for t, _ in sorted(freq.items(), key=lambda x: (-x[1], x[0]))[:5]]
                top_tickers_json = json.dumps(top_tickers, ensure_ascii=False) if top_tickers else None

                cluster = session.query(NewsCluster).filter(NewsCluster.cluster_key == ckey).first()
                if cluster is None:
                    cluster = NewsCluster(
                        cluster_key=ckey,
                        theme_key=theme_key,
                        event_type=event_type,
                        title=title,
                        summary=summary,
                        top_tickers_json=top_tickers_json,
                        published_start=start,
                        published_end=end,
                        published_ymd=published_ymd,
                        article_count=len(members),
                    )
                    session.add(cluster)
                    session.flush()
                else:
                    cluster.theme_key = theme_key
                    cluster.event_type = event_type
                    cluster.title = title
                    cluster.summary = summary
                    cluster.top_tickers_json = top_tickers_json
                    cluster.published_start = start
                    cluster.published_end = end
                    cluster.published_ymd = published_ymd
                    cluster.article_count = len(members)

                stats.clusters_updated += 1
                cluster_id_map[ckey] = int(cluster.id)

            # Mentions (ticker -> article, with optional cluster id)
            for row, ckey, tickers in article_rows:
                if not tickers:
                    continue
                cluster_id = cluster_id_map.get(ckey)
                stats.mentions_inserted += _upsert_mentions(
                    session,
                    article_id=int(row.id),
                    cluster_id=cluster_id,
                    tickers=tickers,
                    published_at=row.published_at,
                    published_ymd=row.published_ymd,
                    event_type=row.event_type,
                    impact=int(row.impact),
                )
    except Exception as e:
        # DB write errors must be logged with full details.
        logger.exception("news upsert failed (db write). data_dir=%s", settings.data_dir)
        messages.append(f"db_write_failed:{type(e).__name__}")
    return stats, messages


def run_news_fetch_once() -> tuple[IngestStats, list[str]]:
    messages: list[str] = []
    all_articles: list[NormalizedArticle] = []
    max_items = max(1, min(int(settings.news_max_items_per_feed), 200))

    if getattr(settings, "news_enable_dart", True):
        arts, errs = fetch_dart(limit=max_items)
        all_articles.extend(arts)
        messages.extend(errs)

    if getattr(settings, "news_enable_rss", True):
        for fid, url in _parse_rss_feeds_config():
            arts, errs = fetch_rss_feed(fid, url, limit=max_items)
            all_articles.extend(arts)
            messages.extend(errs)

    stats, db_msgs = upsert_articles(all_articles)
    messages.extend(db_msgs)
    return stats, messages


def backfill_ticker_news_once(
    ticker: str,
    *,
    per_feed_limit: int = 40,
    max_elapsed_s: float = 8.0,
    rss_timeout_sec: int = 4,
    naver_timeout_sec: int = 5,
    include_naver_search: bool = True,
    include_naver_finance_community: bool = True,
) -> tuple[IngestStats, list[str]]:
    """On-demand supplement fetch for a specific ticker.

    Used by stock-detail article queries on MISS to reduce false blanks.
    """
    messages: list[str] = []
    ticker_i = str(ticker or "").strip()
    if not re.fullmatch(r"\d{6}", ticker_i):
        return IngestStats(), ["ticker_backfill: invalid_ticker"]

    stock_name = ticker_primary_name(ticker_i)
    if not stock_name:
        return IngestStats(), ["ticker_backfill: ticker_name_missing"]

    lim = max(10, min(int(per_feed_limit), 120))
    all_articles: list[NormalizedArticle] = []
    started_at = now_kst()
    elapsed_s = lambda: (now_kst() - started_at).total_seconds()

    feeds = _parse_rss_feeds_config()
    feed_priority = {
        "biz_chosun": 0,
        "hankyung_finance": 1,
        "mk_stock": 2,
        "yna_market": 3,
        "newsis_economy": 4,
        "newsis_bank": 5,
        "hankyung_all": 6,
        "mk_economy": 7,
        "yna_economy": 8,
        "newsis_sokbo": 9,
        "sbs_rss": 10,
    }
    feeds.sort(key=lambda x: (feed_priority.get(str(x[0]), 99), str(x[0])))

    # 1) Naver search supplement first (highest recall for ticker-centric news).
    naver_search_enabled = include_naver_search and getattr(settings, "news_enable_naver_search_fallback", True)
    if naver_search_enabled and elapsed_s() < max_elapsed_s:
        arts, errs = fetch_naver_news_search(
            stock_name=stock_name,
            stock_ticker=ticker_i,
            limit=min(60, max(15, lim)),
            page_starts=[1, 11, 21, 31],
            timeout_sec=naver_timeout_sec,
        )
        all_articles.extend(arts)
        messages.extend(errs)
    elif naver_search_enabled:
        messages.append("ticker_backfill: naver_skipped_by_budget")

    # 2) Naver Finance community supplement (discussion board content).
    naver_community_enabled = include_naver_finance_community and getattr(settings, "news_enable_naver_finance_community_fallback", True)
    if naver_community_enabled and elapsed_s() < max_elapsed_s:
        community_pages = max(1, min(int(getattr(settings, "news_naver_finance_community_pages", 2)), 5))
        arts, errs = fetch_naver_finance_community(
            stock_name=stock_name,
            stock_ticker=ticker_i,
            limit=min(40, max(10, lim)),
            pages=community_pages,
            timeout_sec=max(3, naver_timeout_sec),
        )
        all_articles.extend(arts)
        messages.extend(errs)
    elif naver_community_enabled:
        messages.append("ticker_backfill: community_skipped_by_budget")

    # 3) RSS focus scan.
    if getattr(settings, "news_enable_rss", True):
        for fid, url in feeds:
            if elapsed_s() >= max_elapsed_s:
                messages.append("ticker_backfill: time_budget_exceeded(rss)")
                break
            arts, errs = fetch_rss_feed(
                fid,
                url,
                limit=lim,
                focus_name=stock_name,
                focus_ticker=ticker_i,
                bypass_relevance_on_focus=True,
                timeout_sec=rss_timeout_sec,
            )
            messages.extend(errs)
            for a in arts:
                combined = f"{a.title} {a.summary or ''}".strip()
                if ticker_i in (a.tickers or []) or _contains_name(combined, stock_name):
                    all_articles.append(a)

    # 4) DART supplement.
    if getattr(settings, "news_enable_dart", True) and elapsed_s() < max_elapsed_s:
        arts, errs = fetch_dart(limit=min(80, max(20, lim)))
        messages.extend(errs)
        for a in arts:
            if ticker_i in (a.tickers or []):
                all_articles.append(a)
    elif getattr(settings, "news_enable_dart", True):
        messages.append("ticker_backfill: dart_skipped_by_budget")

    stats, db_msgs = upsert_articles(all_articles)
    messages.extend(db_msgs)
    messages.append(f"ticker_backfill:{ticker_i}:articles={len(all_articles)}:elapsed={elapsed_s():.2f}s")
    return stats, messages


# --- News fetch queue / single worker ---

_NEWS_QUEUE: Queue[str] = Queue()
_NEWS_LOCK = Lock()
_NEWS_IN_FLIGHT = False
_NEWS_LAST: dict[str, Any] = {"ts": None, "status": "MISS", "message": None}
_TICKER_BACKFILL_LAST_AT: dict[str, datetime] = {}
_TICKER_BACKFILL_LOCK = Lock()


def should_run_ticker_backfill(ticker: str, *, cooldown_s: int = 180) -> bool:
    tk = str(ticker or "").strip()
    if not re.fullmatch(r"\d{6}", tk):
        return False
    now_ts = now_kst()
    with _TICKER_BACKFILL_LOCK:
        last = _TICKER_BACKFILL_LAST_AT.get(tk)
        if last is not None:
            elapsed = (now_ts - last).total_seconds()
            if elapsed < max(1, int(cooldown_s)):
                return False
        _TICKER_BACKFILL_LAST_AT[tk] = now_ts
        return True


def enqueue_news_fetch(reason: str = "scheduler") -> bool:
    global _NEWS_IN_FLIGHT
    with _NEWS_LOCK:
        if _NEWS_IN_FLIGHT:
            return False
        _NEWS_IN_FLIGHT = True
        _NEWS_QUEUE.put(reason)
        return True


def last_news_fetch_status() -> dict[str, Any]:
    with _NEWS_LOCK:
        return dict(_NEWS_LAST)


def _news_worker() -> None:
    global _NEWS_IN_FLIGHT, _NEWS_LAST
    while True:
        reason = _NEWS_QUEUE.get()
        try:
            stats, msgs = run_news_fetch_once()
            msg = None
            if msgs:
                # Keep it bounded for logs/meta.
                msg = "; ".join(msgs[:6])
            with _NEWS_LOCK:
                _NEWS_LAST = {
                    "ts": now_kst(),
                    "status": "OK" if (stats.inserted + stats.updated) > 0 else "MISS",
                    "message": msg,
                    "reason": reason,
                }
        except Exception:
            logger.exception("news worker failed")
            with _NEWS_LOCK:
                _NEWS_LAST = {"ts": now_kst(), "status": "ERROR", "message": "worker_failed", "reason": reason}
        finally:
            with _NEWS_LOCK:
                _NEWS_IN_FLIGHT = False
            _NEWS_QUEUE.task_done()


_NEWS_THREAD_STARTED = False


def ensure_news_worker_started() -> None:
    global _NEWS_THREAD_STARTED
    if _NEWS_THREAD_STARTED:
        return
    _NEWS_THREAD_STARTED = True
    Thread(target=_news_worker, daemon=True).start()


# Start on import for consistent background behavior (daemon thread).
ensure_news_worker_started()
