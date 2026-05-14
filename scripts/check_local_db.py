#!/usr/bin/env python3
import sqlite3
from datetime import datetime
from pathlib import Path

DB_NAME = "longterm_stock_picker.db"


def find_database() -> Path | None:
    root = Path.cwd()
    candidates = []
    for base in [
        root,
        root / "data",
        root / "outputs",
        root / "app",
        Path.home(),
    ]:
        if base.exists():
            candidates.extend(base.rglob(DB_NAME))
    candidates = [p for p in candidates if p.is_file()]
    if not candidates:
        return None
    return max(candidates, key=lambda p: p.stat().st_mtime)


def table_count(conn: sqlite3.Connection, table: str) -> int:
    try:
        return int(conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])
    except sqlite3.Error:
        return 0


def scalar(conn: sqlite3.Connection, sql: str) -> str | None:
    try:
        row = conn.execute(sql).fetchone()
        return None if row is None else row[0]
    except sqlite3.Error:
        return None


def has_ten_years(first: str | None, latest: str | None) -> bool:
    if not first or not latest or len(first) != 8 or len(latest) != 8:
        return False
    try:
        first_date = datetime.strptime(first, "%Y%m%d")
        latest_date = datetime.strptime(latest, "%Y%m%d")
    except ValueError:
        return False
    return (latest_date - first_date).days >= 3650


def main() -> None:
    db_path = find_database()
    if db_path is None:
        print("电脑端数据库尚未初始化")
        return

    with sqlite3.connect(db_path) as conn:
        first_trade_date = scalar(conn, "SELECT MIN(tradeDate) FROM daily_quotes")
        latest_trade_date = scalar(conn, "SELECT MAX(tradeDate) FROM daily_quotes")
        print(f"数据库路径: {db_path}")
        print(f"stock_basic 数量: {table_count(conn, 'stock_basic')}")
        print(f"daily_quotes 数量: {table_count(conn, 'daily_quotes')}")
        print(f"financial_snapshot 数量: {table_count(conn, 'financial_snapshot')}")
        print(f"score_result 数量: {table_count(conn, 'score_result')}")
        print(f"daily_quotes 最早交易日: {first_trade_date or '—'}")
        print(f"daily_quotes 最新交易日: {latest_trade_date or '—'}")
        print(f"是否满足至少10年数据: {has_ten_years(first_trade_date, latest_trade_date)}")


if __name__ == "__main__":
    main()
