from __future__ import annotations

import logging
import random
import re
import time
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Any, Iterable, TypeVar
from urllib.parse import urljoin
from xml.etree import ElementTree as ET

import requests

try:
    # GitHub: https://github.com/jadchaar/sec-edgar-api
    # Optional dependency. If unavailable or runtime-incompatible, code falls back to native collectors.
    from sec_edgar_api import EdgarClient as SecEdgarApiClient  # type: ignore
except Exception:  # pragma: no cover - optional runtime dependency
    SecEdgarApiClient = None


logger = logging.getLogger("stock.us_insiders")
T = TypeVar("T")


ATOM_NS = {"atom": "http://www.w3.org/2005/Atom"}
# Archive URLs can include an intermediate accession-number directory.
# Match the canonical dashed accession file name itself.
ACC_RE = re.compile(r"/(\d{10}-\d{2}-\d{6})-index\.htm", re.IGNORECASE)
ACC_TXT_RE = re.compile(r"(\d{10}-\d{2}-\d{6})\.txt$", re.IGNORECASE)
INDEX_PATH_RE = re.compile(r"/data/(\d+)/(\d+)/(\d{10}-\d{2}-\d{6})-index\.htm", re.IGNORECASE)
CIK_IN_URL_RE = re.compile(r"/edgar/data/(\d+)/", re.IGNORECASE)
FILING_DATE_RE = re.compile(r"Filing Date</div>\s*<div class=\"info\">(\d{4}-\d{2}-\d{2})", re.IGNORECASE)
XML_HREF_RE = re.compile(r"href=\"([^\"]+?\.xml)\"", re.IGNORECASE)
TENB5_RE = re.compile(r"10b5[\s\-]?1", re.IGNORECASE)
US_TICKER_RE = re.compile(r"^[A-Z][A-Z0-9.\-]{0,11}$")
XSL_PATH_RE = re.compile(r"/xslf345x\d+/", re.IGNORECASE)
XML_BLOCK_RE = re.compile(r"<XML>(.*?)</XML>", re.IGNORECASE | re.DOTALL)
SUPPORTED_TX_CODES = ("P", "A", "M", "F")
SUPPORTED_TX_CODE_SET = set(SUPPORTED_TX_CODES)


@dataclass
class FilingEntry:
    accession_no: str
    filing_date: date | None
    index_url: str | None = None
    txt_url: str | None = None


@dataclass
class PurchaseRow:
    ticker: str
    company_name: str
    cik: str
    executive_name: str
    executive_role: str
    transaction_date: date
    filing_date: date | None
    shares: float
    price: float
    total_value_usd: float
    transaction_code: str
    acquired_disposed_code: str | None
    accession_no: str
    source_url: str
    has_10b5_1: bool


def _lname(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag


def _child(node: ET.Element | None, name: str) -> ET.Element | None:
    if node is None:
        return None
    for c in list(node):
        if _lname(c.tag) == name:
            return c
    return None


def _text(node: ET.Element | None) -> str | None:
    if node is None:
        return None
    value = (node.text or "").strip()
    return value if value else None


def _nested_text(node: ET.Element | None, *path: str) -> str | None:
    cur = node
    for p in path:
        cur = _child(cur, p)
        if cur is None:
            return None
    return _text(cur)


def _to_date(raw: str | None) -> date | None:
    if not raw:
        return None
    s = raw.strip()
    if not s:
        return None
    for fmt in ("%Y-%m-%d", "%Y%m%d", "%m/%d/%Y", "%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%dT%H:%M:%SZ"):
        try:
            dt = datetime.strptime(s, fmt)
            return dt.date()
        except Exception:
            continue
    # Fallback: keep only leading ISO date.
    if len(s) >= 10:
        try:
            return datetime.strptime(s[:10], "%Y-%m-%d").date()
        except Exception:
            return None
    return None


def _to_float(raw: str | None) -> float | None:
    if raw is None:
        return None
    s = raw.replace(",", "").strip()
    if not s:
        return None
    try:
        return float(s)
    except Exception:
        return None


def _is_true(raw: str | None) -> bool:
    return str(raw or "").strip().lower() in ("1", "true", "yes", "y")


def normalize_transaction_codes(raw: str | Iterable[str] | None) -> list[str]:
    if raw is None:
        return list(SUPPORTED_TX_CODES)
    if isinstance(raw, str):
        tokens = [x.strip().upper() for x in raw.replace(" ", "").split(",") if x.strip()]
    else:
        tokens = [str(x or "").strip().upper() for x in raw if str(x or "").strip()]
    if not tokens or "ALL" in tokens:
        return list(SUPPORTED_TX_CODES)
    out: list[str] = []
    seen: set[str] = set()
    for code in tokens:
        if code in SUPPORTED_TX_CODE_SET and code not in seen:
            seen.add(code)
            out.append(code)
    return out if out else list(SUPPORTED_TX_CODES)


def _subtract_trading_days(base: date, trading_days: int) -> date:
    target = max(1, int(trading_days))
    d = base
    seen = 0
    while seen < (target - 1):
        d -= timedelta(days=1)
        if d.weekday() < 5:
            seen += 1
    return d


def _classify_role(title: str | None) -> str | None:
    t = (title or "").strip().lower()
    if not t:
        return None
    if "chief executive officer" in t or re.search(r"\bceo\b", t) or "principal executive" in t:
        return "CEO"
    if "chief financial officer" in t or re.search(r"\bcfo\b", t) or "principal financial" in t:
        return "CFO"
    return None


def _extract_accession(index_url: str) -> str | None:
    m = ACC_RE.search(index_url)
    if not m:
        return None
    return m.group(1)


def _quarter_of(d: date) -> int:
    return ((d.month - 1) // 3) + 1


def _request_with_retry(
    session: requests.Session,
    url: str,
    *,
    headers: dict[str, str],
    timeout_sec: int,
    retries: int = 2,
    backoff_sec: float = 0.35,
) -> requests.Response:
    last_err: Exception | None = None
    for attempt in range(max(1, retries + 1)):
        try:
            resp = session.get(url, headers=headers, timeout=timeout_sec)
            # SEC can intermittently rate-limit or return transient 5xx.
            if resp.status_code in (429, 500, 502, 503, 504) and attempt < retries:
                retry_after = resp.headers.get("Retry-After")
                wait_sec = float(retry_after) if (retry_after and retry_after.isdigit()) else backoff_sec * (attempt + 1)
                time.sleep(wait_sec + random.uniform(0.05, 0.25))
                continue
            return resp
        except requests.RequestException as ex:
            last_err = ex
            if attempt >= retries:
                raise
            time.sleep(backoff_sec * (attempt + 1) + random.uniform(0.05, 0.25))
    if last_err is not None:
        raise last_err
    raise RuntimeError("request failed without exception")


def _sample_evenly(items: list[T], limit: int) -> list[T]:
    if limit <= 0:
        return []
    if len(items) <= limit:
        return items
    # Spread picks across the whole day index to avoid over-biasing the head of file.
    picks = sorted({int(i * len(items) / limit) for i in range(limit)})
    out = [items[i] for i in picks if 0 <= i < len(items)]
    if len(out) >= limit:
        return out[:limit]
    # Fill any shortfall from the tail.
    for x in reversed(items):
        if x not in out:
            out.append(x)
            if len(out) >= limit:
                break
    return out[:limit]


def _fetch_daily_index_form4_entries(
    session: requests.Session,
    headers: dict[str, str],
    timeout_sec: int,
    max_entries: int,
    lookback_calendar_days: int,
    base_date: date | None = None,
) -> tuple[list[FilingEntry], bool]:
    results: list[FilingEntry] = []
    seen: set[str] = set()
    rate_limited = False
    today = base_date or datetime.now(timezone.utc).date()
    # Keep candidate coverage across days instead of taking only the newest day head.
    effective_days = max(1, int(lookback_calendar_days))
    # Balance head freshness and day coverage:
    # previous "2~3 days 집중" cap made 10/20/30-day windows effectively scan
    # only the most recent filings. Spread pulls across many days first.
    spread_days = max(10, min(effective_days, 20))
    per_day_cap = max(8, min(40, (max_entries + spread_days - 1) // spread_days))

    for offset in range(effective_days):
        day = today - timedelta(days=offset)
        qtr = _quarter_of(day)
        idx_url = (
            "https://www.sec.gov/Archives/edgar/daily-index/"
            f"{day.year}/QTR{qtr}/master.{day.strftime('%Y%m%d')}.idx"
        )

        try:
            resp = _request_with_retry(
                session,
                idx_url,
                headers=headers,
                timeout_sec=timeout_sec,
            )
        except requests.RequestException:
            logger.warning("SEC daily index request failed for %s", day.isoformat())
            continue
        if resp.status_code == 404:
            continue
        if resp.status_code == 403:
            # 403 can happen on some calendar days/routes; continue and rely on atom fallback.
            logger.info("SEC daily index forbidden (403) for %s", day.isoformat())
            continue
        if resp.status_code == 429:
            logger.warning("SEC rate limit hit while fetching daily index (%s)", day.isoformat())
            rate_limited = True
            break
        if resp.status_code >= 400:
            logger.warning("SEC daily index non-200 status=%s day=%s", resp.status_code, day.isoformat())
            continue

        day_entries: list[FilingEntry] = []
        for raw in resp.text.splitlines():
            if "|" not in raw:
                continue
            parts = raw.split("|")
            if len(parts) != 5:
                continue
            form_type = parts[2].strip().upper()
            if not form_type.startswith("4"):
                continue

            file_name = parts[4].strip().lstrip("/")
            m = ACC_TXT_RE.search(file_name)
            if not m:
                continue

            accession = m.group(1)
            if accession in seen:
                continue
            seen.add(accession)

            filing_date = _to_date(parts[3].strip())
            txt_url = f"https://www.sec.gov/Archives/{file_name}"
            seg = file_name.split("/")
            cik = seg[2] if len(seg) > 2 else ""
            accession_nodash = accession.replace("-", "")
            index_url = (
                f"https://www.sec.gov/Archives/edgar/data/{cik}/{accession_nodash}/{accession}-index.htm"
                if cik
                else None
            )
            day_entries.append(
                FilingEntry(
                    accession_no=accession,
                    filing_date=filing_date,
                    index_url=index_url,
                    txt_url=txt_url,
                )
            )

        if not day_entries:
            continue
        remain = max_entries - len(results)
        if remain <= 0:
            break
        pick_n = min(remain, per_day_cap)
        if len(day_entries) <= pick_n:
            picked = day_entries
        else:
            # Keep a head slice (fresh filings) and sample the remainder across
            # the day file so mid/tail filings are not starved.
            head_n = min(pick_n, max(4, int(pick_n * 0.6)))
            head = day_entries[:head_n]
            tail_slots = max(0, pick_n - head_n)
            tail = _sample_evenly(day_entries[head_n:], tail_slots) if tail_slots > 0 else []
            picked = head + tail
        results.extend(picked[:pick_n])
        if len(results) >= max_entries:
            break

        # Keep index-file polling gentle.
        time.sleep(0.12)
    return results, rate_limited


def _fetch_atom_form4_entries(
    session: requests.Session,
    headers: dict[str, str],
    timeout_sec: int,
    max_entries: int,
) -> tuple[list[FilingEntry], bool]:
    results: list[FilingEntry] = []
    seen: set[str] = set()
    rate_limited = False

    for start in range(0, max_entries, 100):
        url = (
            "https://www.sec.gov/cgi-bin/browse-edgar"
            f"?action=getcurrent&type=4&owner=only&count=100&start={start}&output=atom"
        )
        try:
            resp = _request_with_retry(
                session,
                url,
                headers=headers,
                timeout_sec=timeout_sec,
            )
        except requests.RequestException:
            logger.warning("SEC atom feed request failed (start=%s)", start)
            break
        if resp.status_code == 429:
            logger.warning("SEC rate limit hit while fetching atom feed (start=%s)", start)
            rate_limited = True
            break
        if resp.status_code == 403:
            logger.warning("SEC atom feed forbidden (403) (start=%s)", start)
            continue
        resp.raise_for_status()
        root = ET.fromstring(resp.text)
        entries = root.findall("atom:entry", ATOM_NS)
        if not entries:
            break

        for e in entries:
            link = e.find("atom:link", ATOM_NS)
            href = (link.attrib.get("href") or "").strip() if link is not None else ""
            if not href:
                continue
            accession = _extract_accession(href)
            if not accession or accession in seen:
                continue
            seen.add(accession)
            filed = _to_date(e.findtext("atom:updated", default="", namespaces=ATOM_NS))
            txt_url: str | None = None
            m = INDEX_PATH_RE.search(href)
            if m:
                cik = m.group(1)
                acc = m.group(3)
                txt_url = f"https://www.sec.gov/Archives/edgar/data/{cik}/{acc.replace('-', '')}/{acc}.txt"
            results.append(FilingEntry(accession_no=accession, filing_date=filed, index_url=href, txt_url=txt_url))
            if len(results) >= max_entries:
                return results, rate_limited
    return results, rate_limited


def _merge_candidates(primary: list[FilingEntry], secondary: list[FilingEntry], max_entries: int) -> list[FilingEntry]:
    # Keep primary ordering stable, but reserve a slice for secondary source so
    # current-feed candidates are not starved when daily-index alone fills slots.
    merged: dict[str, FilingEntry] = {}
    primary_order: list[str] = []
    secondary_order: list[str] = []

    def upsert(entry: FilingEntry, order: list[str]) -> None:
        key = entry.accession_no
        cur = merged.get(key)
        if cur is None:
            merged[key] = entry
            order.append(key)
            return
        merged[key] = FilingEntry(
            accession_no=key,
            filing_date=cur.filing_date or entry.filing_date,
            index_url=cur.index_url or entry.index_url,
            txt_url=cur.txt_url or entry.txt_url,
        )

    for entry in primary:
        upsert(entry, primary_order)
    for entry in secondary:
        upsert(entry, secondary_order)

    if not primary_order and not secondary_order:
        return []
    if not secondary_order:
        return [merged[k] for k in primary_order[:max_entries]]

    reserve_for_secondary = min(len(secondary_order), max(24, (max_entries * 2) // 3))
    primary_head_n = max(0, max_entries - reserve_for_secondary)

    selected: list[str] = []
    seen: set[str] = set()
    for key in primary_order[:primary_head_n]:
        if key in seen:
            continue
        seen.add(key)
        selected.append(key)

    if len(secondary_order) <= reserve_for_secondary:
        secondary_pick = secondary_order
    else:
        sec_head_n = min(reserve_for_secondary, max(8, int(reserve_for_secondary * 0.4)))
        sec_tail_slots = max(0, reserve_for_secondary - sec_head_n)
        secondary_pick = secondary_order[:sec_head_n]
        if sec_tail_slots > 0:
            tail = secondary_order[sec_head_n:]
            # Quantile neighborhood sampling improves hit-rate for mid-ranked filings.
            base_slots = max(1, (sec_tail_slots + 2) // 3)
            sampled_tail: list[str] = []
            seen_tail: set[str] = set()
            for i in range(base_slots):
                pos = int(i * len(tail) / base_slots)
                for off in (-1, 0, 1):
                    idx = pos + off
                    if 0 <= idx < len(tail):
                        key = tail[idx]
                        if key in seen_tail:
                            continue
                        seen_tail.add(key)
                        sampled_tail.append(key)
                    if len(sampled_tail) >= sec_tail_slots:
                        break
                if len(sampled_tail) >= sec_tail_slots:
                    break
            secondary_pick.extend(sampled_tail[:sec_tail_slots])

    for key in secondary_pick:
        if key in seen:
            continue
        seen.add(key)
        selected.append(key)
        if len(selected) >= max_entries:
            break

    if len(selected) < max_entries:
        for key in primary_order[primary_head_n:]:
            if key in seen:
                continue
            seen.add(key)
            selected.append(key)
            if len(selected) >= max_entries:
                break

    return [merged[k] for k in selected[:max_entries]]


def _extract_cik_from_entry(entry: FilingEntry) -> str | None:
    for raw in (entry.index_url or "", entry.txt_url or ""):
        if not raw:
            continue
        m = INDEX_PATH_RE.search(raw)
        if m:
            return str(int(m.group(1)))
        m = CIK_IN_URL_RE.search(raw)
        if m:
            return str(int(m.group(1)))
    return None


def _fetch_secapi_form4_entries(
    *,
    seed_entries: list[FilingEntry],
    user_agent: str,
    base_date: date,
    max_entries: int,
    cik_limit: int,
    max_per_cik: int,
) -> list[FilingEntry]:
    if SecEdgarApiClient is None:
        return []
    if max_entries <= 0 or cik_limit <= 0 or max_per_cik <= 0:
        return []

    # Keep the enrichment window pragmatic so requests stay bounded.
    min_filing_date = _subtract_trading_days(base_date, 60)
    seen_accessions = {x.accession_no for x in seed_entries if x.accession_no}
    ciks: list[str] = []
    seen_ciks: set[str] = set()
    for e in seed_entries:
        cik = _extract_cik_from_entry(e)
        if not cik or cik in seen_ciks:
            continue
        seen_ciks.add(cik)
        ciks.append(cik)
        if len(ciks) >= cik_limit:
            break

    if not ciks:
        return []

    out: list[FilingEntry] = []
    client = SecEdgarApiClient(user_agent=user_agent)
    for cik in ciks:
        try:
            payload = client.get_submissions(cik, handle_pagination=False)
        except Exception:
            continue

        filings = payload.get("filings") or {}
        recent = filings.get("recent") or {}
        forms = recent.get("form") or []
        accessions = recent.get("accessionNumber") or []
        filing_dates = recent.get("filingDate") or []

        per_cik = 0
        n = min(len(forms), len(accessions), len(filing_dates))
        for i in range(n):
            form = str(forms[i] or "").upper()
            if not form.startswith("4"):
                continue
            accession = str(accessions[i] or "").strip()
            if not accession or accession in seen_accessions:
                continue
            filed = _to_date(str(filing_dates[i] or ""))
            if filed is not None and filed < min_filing_date:
                continue

            seen_accessions.add(accession)
            acc_nodash = accession.replace("-", "")
            index_url = f"https://www.sec.gov/Archives/edgar/data/{cik}/{acc_nodash}/{accession}-index.htm"
            txt_url = f"https://www.sec.gov/Archives/edgar/data/{cik}/{acc_nodash}/{accession}.txt"
            out.append(
                FilingEntry(
                    accession_no=accession,
                    filing_date=filed,
                    index_url=index_url,
                    txt_url=txt_url,
                )
            )
            per_cik += 1
            if per_cik >= max_per_cik or len(out) >= max_entries:
                break
        if len(out) >= max_entries:
            break
    return out


def _extract_ownership_xml_from_submission(text: str) -> str | None:
    for block in XML_BLOCK_RE.findall(text):
        if "<ownershipdocument" in block.lower():
            return block.strip()

    lower = text.lower()
    start = lower.find("<ownershipdocument")
    if start < 0:
        return None
    end = lower.find("</ownershipdocument>", start)
    if end < 0:
        return None
    end += len("</ownershipdocument>")
    return text[start:end].strip()


def _resolve_filing_xml_url(
    session: requests.Session,
    headers: dict[str, str],
    timeout_sec: int,
    index_url: str,
) -> tuple[str | None, date | None]:
    resp = _request_with_retry(
        session,
        index_url,
        headers=headers,
        timeout_sec=timeout_sec,
    )
    resp.raise_for_status()
    html = resp.text
    filing_date = _to_date(FILING_DATE_RE.search(html).group(1)) if FILING_DATE_RE.search(html) else None

    hrefs = XML_HREF_RE.findall(html)
    if not hrefs:
        return None, filing_date

    candidates: list[str] = []
    seen: set[str] = set()
    for href in hrefs:
        full = urljoin(index_url, href)
        if not full.lower().endswith(".xml"):
            continue

        # SEC index often exposes both:
        #   /.../xslF345X05/<file>.xml   (HTML transformed view)
        #   /.../<file>.xml              (raw XML payload)
        # We must parse the raw XML path.
        normalized = XSL_PATH_RE.sub("/", full)
        for c in (normalized, full):
            if c not in seen:
                seen.add(c)
                candidates.append(c)

    def rank_href(full_url: str) -> tuple[int, int, int]:
        h = full_url.lower()
        score = 0
        if XSL_PATH_RE.search(h):
            score -= 100
        else:
            score += 50
        if h.endswith("ownership.xml"):
            score += 20
        if "form4" in h:
            score += 10
        if "primary" in h:
            score += 5
        if "schema" in h or h.endswith(".xsd"):
            score -= 30
        # Tie-breaker: shorter path tends to be the raw file path.
        return score, -len(h), 0

    for full in sorted(candidates, key=rank_href, reverse=True):
        if full.lower().endswith(".xml"):
            return full, filing_date
    return None, filing_date


def _extract_footnotes(root: ET.Element) -> dict[str, str]:
    out: dict[str, str] = {}
    for n in root.iter():
        if _lname(n.tag) != "footnote":
            continue
        fid = (n.attrib.get("id") or n.attrib.get("ID") or "").strip()
        txt = (n.text or "").strip()
        if fid and txt:
            out[fid] = txt
    return out


def _has_10b5_flag(tx_node: ET.Element, footnotes: dict[str, str], remarks_text: str | None) -> bool:
    if remarks_text and TENB5_RE.search(remarks_text):
        return True
    ids: set[str] = set()
    for n in tx_node.iter():
        if _lname(n.tag) != "footnoteId":
            continue
        fid = (n.attrib.get("id") or n.attrib.get("ID") or "").strip()
        if fid:
            ids.add(fid)
    for fid in ids:
        if TENB5_RE.search(footnotes.get(fid, "")):
            return True
    return False


def _parse_form4_xml(
    xml_text: str,
    accession_no: str,
    source_url: str,
    filing_date_hint: date | None,
    allowed_codes: set[str],
) -> list[PurchaseRow]:
    root = ET.fromstring(xml_text)
    issuer = _child(root, "issuer")
    ticker = (_nested_text(issuer, "issuerTradingSymbol") or "").strip().upper()
    company_name = (_nested_text(issuer, "issuerName") or "").strip()
    cik = (_nested_text(issuer, "issuerCik") or "").strip().lstrip("0")
    if not ticker or not US_TICKER_RE.match(ticker):
        return []

    owners: list[tuple[str, str]] = []
    for r in root.iter():
        if _lname(r.tag) != "reportingOwner":
            continue
        name = (_nested_text(r, "reportingOwnerId", "rptOwnerName") or "").strip()
        rel = _child(r, "reportingOwnerRelationship")
        title = _nested_text(rel, "officerTitle")
        role = _classify_role(title)
        if role is None:
            if _is_true(_nested_text(rel, "isOfficer")):
                role = "OFFICER"
            elif _is_true(_nested_text(rel, "isDirector")):
                role = "DIRECTOR"
            elif _is_true(_nested_text(rel, "isTenPercentOwner")):
                role = "TEN_PCT_OWNER"
            elif _is_true(_nested_text(rel, "isOther")):
                role = "OTHER"
            elif title and title.strip():
                role = "OTHER"
        if not name or role is None:
            continue
        owners.append((name, role))
    if not owners:
        return []

    footnotes = _extract_footnotes(root)
    remarks = _nested_text(root, "remarks")
    parsed: list[PurchaseRow] = []

    filing_date = filing_date_hint or _to_date(_nested_text(root, "periodOfReport"))
    for tx in root.iter():
        if _lname(tx.tag) != "nonDerivativeTransaction":
            continue

        code = (_nested_text(tx, "transactionCoding", "transactionCode") or "").strip().upper()
        if code not in allowed_codes:
            continue

        tx_date = _to_date(_nested_text(tx, "transactionDate", "value"))
        shares = _to_float(_nested_text(tx, "transactionAmounts", "transactionShares", "value"))
        price = _to_float(_nested_text(tx, "transactionAmounts", "transactionPricePerShare", "value"))
        ad_code = (_nested_text(tx, "transactionAmounts", "transactionAcquiredDisposedCode", "value") or "").strip().upper()

        if tx_date is None or shares is None or price is None:
            continue
        if shares <= 0.0 or price <= 0.0:
            continue
        has_10b5_1 = _has_10b5_flag(tx, footnotes, remarks)
        total_value = shares * price
        if ad_code == "D":
            total_value *= -1.0
        for owner_name, owner_role in owners:
            parsed.append(
                PurchaseRow(
                    ticker=ticker,
                    company_name=company_name,
                    cik=cik,
                    executive_name=owner_name,
                    executive_role=owner_role,
                    transaction_date=tx_date,
                    filing_date=filing_date,
                    shares=shares,
                    price=price,
                    total_value_usd=total_value,
                    transaction_code=code,
                    acquired_disposed_code=ad_code if ad_code in ("A", "D") else None,
                    accession_no=accession_no,
                    source_url=source_url,
                    has_10b5_1=has_10b5_1,
                )
            )
    return parsed


def _aggregate_rows(
    rows: list[PurchaseRow],
    *,
    base_date: date,
    trading_days: int,
    target_count: int,
) -> list[dict[str, Any]]:
    window_start = _subtract_trading_days(base_date, trading_days)
    repeat_start = _subtract_trading_days(base_date, 90)

    in_window = [r for r in rows if window_start <= r.transaction_date <= base_date]
    grouped: dict[tuple[str, str, str], list[PurchaseRow]] = {}
    all_by_key: dict[tuple[str, str, str], list[PurchaseRow]] = {}

    for r in rows:
        key = (r.ticker, r.executive_name.strip().upper(), r.transaction_code)
        all_by_key.setdefault(key, []).append(r)
    for r in in_window:
        key = (r.ticker, r.executive_name.strip().upper(), r.transaction_code)
        grouped.setdefault(key, []).append(r)

    items: list[dict[str, Any]] = []
    role_rank = {
        "CEO": 4,
        "CFO": 4,
        "OFFICER": 3,
        "DIRECTOR": 2,
        "TEN_PCT_OWNER": 1,
        "OTHER": 0,
    }
    for key, group in grouped.items():
        latest = max(group, key=lambda x: (x.transaction_date, x.filing_date or x.transaction_date))
        total_shares = sum(x.shares for x in group)
        total_value = sum(x.total_value_usd for x in group)
        if total_shares <= 0.0:
            continue
        buy_dates = sorted({x.transaction_date for x in group})
        if buy_dates:
            buy_date_range = buy_dates[0].isoformat() if len(buy_dates) == 1 else f"{buy_dates[0].isoformat()}~{buy_dates[-1].isoformat()}"
        else:
            buy_date_range = latest.transaction_date.isoformat()
        tx_count = len(group)
        pattern_kind = "단기 분할거래" if len(buy_dates) >= 2 else "단일거래"
        pattern_summary = f"{pattern_kind} {tx_count}회 · 총 {total_shares:,.0f}주 · ${total_value:,.0f}"

        all_rows = all_by_key.get(key, [])
        repeat_dates = sorted({x.transaction_date for x in all_rows if repeat_start <= x.transaction_date <= base_date})
        repeat_count = len(repeat_dates)

        notes: list[str] = []
        if latest.has_10b5_1:
            notes.append("10b5-1 표기 거래")
        if any((x.acquired_disposed_code or "") == "D" for x in group):
            notes.append("처분(D) 거래 포함")
        if repeat_count >= 2:
            notes.append(f"최근 90거래일 내 반복 거래 {repeat_count}회 확인")

        lag_days: int | None = None
        if latest.filing_date is not None:
            try:
                lag_days = max(0, (latest.filing_date - latest.transaction_date).days)
            except Exception:
                lag_days = None

        score = 0
        # Size
        abs_value = abs(total_value)
        if abs_value >= 1_000_000:
            score += 55
        elif abs_value >= 250_000:
            score += 40
        elif abs_value >= 50_000:
            score += 25
        else:
            score += 10
        # Repeat / clustering
        if repeat_count >= 3:
            score += 25
        elif repeat_count == 2:
            score += 15
        if tx_count >= 2:
            score += 10
        # Filing lag freshness
        if lag_days is not None:
            if lag_days <= 1:
                score += 10
            elif lag_days <= 2:
                score += 6
            elif lag_days <= 5:
                score += 2
        # Planned trade penalty
        if latest.has_10b5_1:
            score -= 8
        # Non-P code is more contextual than pure open-market buy signal.
        if latest.transaction_code != "P":
            score -= 12
        score = max(0, min(100, score))

        if score >= 70:
            signal_grade = "STRONG"
        elif score >= 45:
            signal_grade = "MEDIUM"
        else:
            signal_grade = "WATCH"

        reason_bits: list[str] = []
        reason_bits.append(f"구분 {latest.transaction_code}")
        reason_bits.append(f"거래금액 ${total_value:,.0f}")
        if repeat_count >= 2:
            reason_bits.append(f"90거래일 반복 {repeat_count}회")
        if lag_days is not None:
            reason_bits.append(f"공시지연 {lag_days}일")
        if latest.has_10b5_1:
            reason_bits.append("10b5-1 표기")
        signal_reason = " · ".join(reason_bits)

        items.append(
            {
                "ticker": latest.ticker,
                "company_name": latest.company_name,
                "cik": latest.cik,
                "executive_name": latest.executive_name,
                "executive_role": latest.executive_role,
                "transaction_code": latest.transaction_code,
                "acquired_disposed_code": latest.acquired_disposed_code,
                "transaction_date": latest.transaction_date,
                "filing_date": latest.filing_date,
                "buy_dates": buy_dates,
                "buy_date_range": buy_date_range,
                "transaction_count": tx_count,
                "total_shares": total_shares,
                "avg_price_usd": total_value / total_shares,
                "total_value_usd": total_value,
                "pattern_summary": pattern_summary,
                "signal_score": score,
                "signal_grade": signal_grade,
                "signal_reason": signal_reason,
                "repeat_buy_90d": repeat_count >= 2,
                "repeat_count_90d": repeat_count if repeat_count >= 2 else 0,
                "has_10b5_1": latest.has_10b5_1,
                "accession_no": latest.accession_no,
                "source_url": latest.source_url,
                "notes": notes,
            }
        )

    items.sort(
        key=lambda x: (
            role_rank.get(str(x.get("executive_role") or "OTHER"), 0),
            abs(float(x.get("total_value_usd") or 0.0)),
            float(x.get("total_shares") or 0.0),
            str(x.get("transaction_date") or ""),
        ),
        reverse=True,
    )
    return items[:target_count]


def compute_us_insider_screen(
    *,
    target_count: int = 10,
    trading_days: int = 10,
    expand_days: int = 20,
    max_candidates: int = 120,
    user_agent: str,
    timeout_sec: int = 12,
    github_enrich_enabled: bool = True,
    github_enrich_cik_limit: int = 20,
    github_enrich_max_per_cik: int = 3,
    transaction_codes: str | Iterable[str] | None = None,
    base_date: date | None = None,
) -> dict[str, Any]:
    as_of = datetime.now(timezone.utc)
    anchor_date = base_date or as_of.date()

    target_count = max(1, min(int(target_count), 30))
    trading_days = max(3, min(int(trading_days), 30))
    expand_days = max(trading_days, min(int(expand_days), 45))
    max_candidates = max(20, min(int(max_candidates), 300))
    github_enrich_cik_limit = max(0, min(int(github_enrich_cik_limit), 100))
    github_enrich_max_per_cik = max(0, min(int(github_enrich_max_per_cik), 20))
    selected_codes = normalize_transaction_codes(transaction_codes)
    allowed_codes = set(selected_codes)
    codes_label = "/".join(selected_codes)

    headers = {"User-Agent": user_agent, "Accept-Encoding": "gzip, deflate", "Host": "www.sec.gov"}
    session = requests.Session()

    parsed_rows: list[PurchaseRow] = []
    forms_checked = 0
    forms_parsed = 0
    parse_errors = 0
    purchase_rows_total = 0
    purchase_rows_in_requested = 0
    purchase_rows_in_expanded = 0
    purchase_rows_in_effective = 0
    effective_days = trading_days
    expanded = False
    shortage_reason: str | None = None
    github_candidates_count = 0
    daily_candidates_count = 0
    atom_candidates_count = 0
    merged_candidates_count = 0
    github_enrich_note: str | None = None

    try:
        # Prefer daily-index feed first (broader coverage than getcurrent-only feed).
        # Fallback to atom/getcurrent if daily index is empty.
        daily_candidates, daily_rate_limited = _fetch_daily_index_form4_entries(
            session=session,
            headers=headers,
            timeout_sec=timeout_sec,
            max_entries=max_candidates,
            lookback_calendar_days=max(21, expand_days * 3),
            base_date=anchor_date,
        )
        daily_candidates_count = len(daily_candidates)
        # Atom getcurrent feed is for the current stream; for historical backfill
        # requests use daily-index only to avoid mixing unrelated "today" filings.
        if base_date is None:
            atom_budget = max(600, min(2000, max_candidates * 5))
            atom_candidates, atom_rate_limited = _fetch_atom_form4_entries(
                session,
                headers,
                timeout_sec=timeout_sec,
                max_entries=atom_budget,
            )
        else:
            atom_candidates, atom_rate_limited = [], False
        atom_candidates_count = len(atom_candidates)
        feed_rate_limited = daily_rate_limited or atom_rate_limited
        candidates = _merge_candidates(daily_candidates, atom_candidates, max_entries=max_candidates)
        if github_enrich_enabled:
            if SecEdgarApiClient is None:
                github_enrich_note = "GitHub sec-edgar-api 미설치/비호환으로 기본 수집 경로만 사용했습니다."
            else:
                try:
                    secapi_candidates = _fetch_secapi_form4_entries(
                        seed_entries=candidates,
                        user_agent=user_agent,
                        base_date=anchor_date,
                        max_entries=max_candidates,
                        cik_limit=github_enrich_cik_limit,
                        max_per_cik=github_enrich_max_per_cik,
                    )
                    github_candidates_count = len(secapi_candidates)
                    candidates = _merge_candidates(candidates, secapi_candidates, max_entries=max_candidates)
                    if github_candidates_count > 0:
                        github_enrich_note = (
                            f"GitHub sec-edgar-api 보조 수집으로 후보 {github_candidates_count}건을 추가 확인했습니다."
                        )
                    else:
                        github_enrich_note = "GitHub sec-edgar-api 보조 수집을 시도했지만 추가 후보는 없었습니다."
                except Exception:
                    logger.exception("sec-edgar-api enrichment failed")
                    github_enrich_note = "GitHub sec-edgar-api 보조 수집 중 오류가 발생해 기본 수집 경로로 계속 진행했습니다."
        merged_candidates_count = len(candidates)
        if feed_rate_limited and not candidates:
            shortage_reason = "SEC 요청 제한(429)으로 수집이 차단되었습니다. 잠시 후 다시 시도해주세요."
        for entry in candidates:
            forms_checked += 1
            try:
                xml_text: str | None = None
                filing_date = entry.filing_date
                source_url = entry.index_url or ""

                if entry.txt_url:
                    txt_resp = _request_with_retry(
                        session,
                        entry.txt_url,
                        headers=headers,
                        timeout_sec=timeout_sec,
                    )
                    txt_resp.raise_for_status()
                    xml_text = _extract_ownership_xml_from_submission(txt_resp.text)
                    source_url = entry.txt_url
                if (not xml_text) and entry.index_url:
                    # Some filings expose XML only via index-linked raw file.
                    xml_url, filing_date_hint = _resolve_filing_xml_url(
                        session, headers, timeout_sec=timeout_sec, index_url=entry.index_url
                    )
                    if xml_url:
                        xml_resp = _request_with_retry(
                            session,
                            xml_url,
                            headers=headers,
                            timeout_sec=timeout_sec,
                        )
                        xml_resp.raise_for_status()
                        xml_text = xml_resp.text
                        filing_date = filing_date_hint or filing_date
                        source_url = xml_url

                if not xml_text:
                    continue
                rows = _parse_form4_xml(
                    xml_text,
                    accession_no=entry.accession_no,
                    source_url=source_url,
                    filing_date_hint=filing_date,
                    allowed_codes=allowed_codes,
                )
                if rows:
                    parsed_rows.extend(rows)
                forms_parsed += 1
            except requests.HTTPError as ex:
                parse_errors += 1
                code = ex.response.status_code if ex.response is not None else None
                if code == 429:
                    shortage_reason = "SEC 요청 제한(429)으로 일부만 분석했습니다."
                    break
                continue
            except Exception:
                parse_errors += 1
                continue
            finally:
                # Keep SEC request rate gentle (well below 10 req/s guideline).
                time.sleep(0.22)

        items = _aggregate_rows(
            parsed_rows,
            base_date=anchor_date,
            trading_days=trading_days,
            target_count=target_count,
        )
        if len(items) < target_count and expand_days > trading_days:
            expanded = True
            effective_days = expand_days
            items = _aggregate_rows(
                parsed_rows,
                base_date=anchor_date,
                trading_days=expand_days,
                target_count=target_count,
            )

        # Practical fallback: if strict 7/14-trading-day result is sparse, expand
        # to a wider but still bounded window so users can inspect enough examples.
        practical_fallback_used = False
        practical_steps = [max(expand_days, 30), 45, 60]
        for days in practical_steps:
            practical_days = min(60, max(effective_days, days))
            if len(items) >= target_count or practical_days <= effective_days:
                continue
            fallback_items = _aggregate_rows(
                parsed_rows,
                base_date=anchor_date,
                trading_days=practical_days,
                target_count=target_count,
            )
            if len(fallback_items) > len(items):
                items = fallback_items
                expanded = True
                effective_days = practical_days
                practical_fallback_used = True
            if len(items) >= target_count:
                break

        if len(items) < target_count and not shortage_reason:
            shortage_reason = (
                f"조건(내부자 + Non-derivative Code={codes_label} + 거래일 기준 기간 내) 충족 항목이 부족해 "
                f"{len(items)}건만 제공합니다."
            )

        purchase_rows_total = len(parsed_rows)
        requested_start = _subtract_trading_days(anchor_date, trading_days)
        expanded_start = _subtract_trading_days(anchor_date, expand_days)
        effective_start = _subtract_trading_days(anchor_date, effective_days)
        purchase_rows_in_requested = sum(1 for r in parsed_rows if requested_start <= r.transaction_date <= anchor_date)
        purchase_rows_in_expanded = sum(1 for r in parsed_rows if expanded_start <= r.transaction_date <= anchor_date)
        purchase_rows_in_effective = sum(1 for r in parsed_rows if effective_start <= r.transaction_date <= anchor_date)

        notes = [
            "내부자 거래는 신호이며 상승을 보장하지 않습니다.",
            "10b5-1 표기 거래는 자발성 신호가 약할 수 있습니다.",
            "집계 사이트가 아닌 SEC EDGAR Form 4 원문(XML) 파싱 결과만 반영했습니다.",
            f"거래 구분 필터: {codes_label}",
            (
                "후보 수집: "
                f"daily-index {daily_candidates_count}건 · atom {atom_candidates_count}건"
                f"{' · github ' + str(github_candidates_count) + '건' if github_candidates_count > 0 else ''}"
                f" · 병합 {merged_candidates_count}건"
            ),
        ]
        if github_enrich_note:
            notes.append(github_enrich_note)
        if practical_fallback_used:
            notes.append(
                f"기본 {trading_days}/{expand_days}거래일 조건에서 0건이라 실용 확장 {effective_days}거래일 결과를 표시했습니다."
            )

        return {
            "as_of": as_of,
            "requested_trading_days": trading_days,
            "effective_trading_days": effective_days,
            "expanded_window": expanded,
            "selected_transaction_codes": selected_codes,
            "target_count": target_count,
            "returned_count": len(items),
            "candidate_daily_index": daily_candidates_count,
            "candidate_atom": atom_candidates_count,
            "candidate_github": github_candidates_count,
            "candidate_merged": merged_candidates_count,
            "forms_checked": forms_checked,
            "forms_parsed": forms_parsed,
            "parse_errors": parse_errors,
            "purchase_rows_total": purchase_rows_total,
            "purchase_rows_in_requested": purchase_rows_in_requested,
            "purchase_rows_in_expanded": purchase_rows_in_expanded,
            "purchase_rows_in_effective": purchase_rows_in_effective,
            "shortage_reason": shortage_reason,
            "items": items,
            "notes": notes,
        }
    except Exception as exc:
        logger.exception("US insider scan failed: %s", exc)
        return {
            "as_of": as_of,
            "requested_trading_days": trading_days,
            "effective_trading_days": trading_days,
            "expanded_window": False,
            "selected_transaction_codes": selected_codes,
            "target_count": target_count,
            "returned_count": 0,
            "candidate_daily_index": daily_candidates_count,
            "candidate_atom": atom_candidates_count,
            "candidate_github": github_candidates_count,
            "candidate_merged": merged_candidates_count,
            "forms_checked": forms_checked,
            "forms_parsed": forms_parsed,
            "parse_errors": parse_errors,
            "purchase_rows_total": purchase_rows_total,
            "purchase_rows_in_requested": purchase_rows_in_requested,
            "purchase_rows_in_expanded": purchase_rows_in_expanded,
            "purchase_rows_in_effective": purchase_rows_in_effective,
            "shortage_reason": f"EDGAR 수집 실패: {exc}",
            "items": [],
            "notes": [
                "SEC EDGAR 응답 상태 또는 네트워크 환경을 확인해주세요.",
            ],
        }
