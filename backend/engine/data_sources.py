from __future__ import annotations

from datetime import date
import logging

import pandas as pd

try:
    import FinanceDataReader as fdr
except Exception:  # pragma: no cover
    fdr = None

try:
    from pykrx import stock as krx_stock
except Exception:  # pragma: no cover
    krx_stock = None

logger = logging.getLogger("stock.data_sources")


def load_ohlcv(code: str, start: date, end: date) -> pd.DataFrame:
    if fdr is None:
        if krx_stock is None:
            logger.warning("FinanceDataReader not available; returning empty OHLCV for %s", code)
            return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"])
        try:
            df = krx_stock.get_market_ohlcv_by_date(start.strftime("%Y%m%d"), end.strftime("%Y%m%d"), code)
            if df is None or df.empty:
                return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"])
            return df[["Open", "High", "Low", "Close", "Volume"]].copy()
        except Exception:
            logger.exception("pykrx load_ohlcv failed for %s", code)
            return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"])
    try:
        df = fdr.DataReader(code, start, end)
        if df is None or df.empty:
            if krx_stock is None:
                return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"])
            try:
                df = krx_stock.get_market_ohlcv_by_date(start.strftime("%Y%m%d"), end.strftime("%Y%m%d"), code)
                if df is None or df.empty:
                    return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"])
                return df[["Open", "High", "Low", "Close", "Volume"]].copy()
            except Exception:
                logger.exception("pykrx load_ohlcv failed for %s", code)
                return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"])
        cols = [c for c in ["Open", "High", "Low", "Close", "Volume"] if c in df.columns]
        out = df[cols].copy()
        if len(cols) < 5:
            return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"])
        return out
    except Exception:
        logger.exception("load_ohlcv failed for %s", code)
        return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"])


def load_benchmark(market: str, start: date, end: date) -> pd.DataFrame:
    code = "KQ11" if market in ("KOSDAQ", "KOSDAQ_ONLY") else "KS11"
    if fdr is None:
        if krx_stock is None:
            logger.warning("FinanceDataReader not available; returning empty benchmark for %s", market)
            return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"])
        try:
            index_code = "2001" if market in ("KOSDAQ", "KOSDAQ_ONLY") else "1001"
            df = krx_stock.get_index_ohlcv_by_date(start.strftime("%Y%m%d"), end.strftime("%Y%m%d"), index_code)
            if df is None or df.empty:
                return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"])
            return df[["Open", "High", "Low", "Close", "Volume"]].copy()
        except Exception:
            logger.exception("pykrx load_benchmark failed for %s", market)
            return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"])
    try:
        df = fdr.DataReader(code, start, end)
        if df is None or df.empty:
            if krx_stock is None:
                return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"])
            try:
                index_code = "2001" if market in ("KOSDAQ", "KOSDAQ_ONLY") else "1001"
                df = krx_stock.get_index_ohlcv_by_date(start.strftime("%Y%m%d"), end.strftime("%Y%m%d"), index_code)
                if df is None or df.empty:
                    return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"])
                return df[["Open", "High", "Low", "Close", "Volume"]].copy()
            except Exception:
                logger.exception("pykrx load_benchmark failed for %s", market)
                return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"])
        return df[[c for c in ["Open", "High", "Low", "Close", "Volume"] if c in df.columns]].copy()
    except Exception:
        logger.exception("load_benchmark failed for %s", market)
        return pd.DataFrame(columns=["Open", "High", "Low", "Close", "Volume"])
