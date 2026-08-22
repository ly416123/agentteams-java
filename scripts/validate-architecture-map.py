#!/usr/bin/env python3
"""Check that the curated architecture map covers every Maven module."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
POM = ROOT / "pom.xml"
MAP = ROOT / "docs" / "architecture-map.html"


def fail(message: str) -> None:
    print(f"ARCHITECTURE_MAP_FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def maven_modules() -> list[str]:
    try:
        root = ET.parse(POM).getroot()
    except (ET.ParseError, OSError) as error:
        fail(f"cannot parse {POM}: {error}")
    modules = root.find("{*}modules")
    if modules is None:
        fail("root pom.xml has no <modules> section")
    names = [module.text.strip() for module in modules.findall("{*}module") if module.text and module.text.strip()]
    if not names:
        fail("root pom.xml declares no modules")
    return names


def map_modules() -> dict[str, str]:
    try:
        content = MAP.read_text(encoding="utf-8")
    except OSError as error:
        fail(f"cannot read {MAP}: {error}")

    titles = re.findall(r'<span class="mod-title">\s*([^<]+?)\s*</span>', content)
    entries: dict[str, str] = {}
    for title in titles:
        module, separator, _ = title.partition(" — ")
        if not separator:
            fail(f"module title has no description separator: {title!r}")
        if module in entries:
            fail(f"duplicate map entry for module {module!r}")
        entries[module] = title
    if not entries:
        fail("architecture map contains no module cards")
    return entries


def main() -> int:
    modules = maven_modules()
    entries = map_modules()
    module_set = set(modules)
    entry_set = set(entries)
    missing = sorted(module_set - entry_set)
    extra = sorted(entry_set - module_set)
    if missing:
        fail(f"Maven modules missing from map: {', '.join(missing)}")
    if extra:
        fail(f"map entries are not Maven modules: {', '.join(extra)}")

    content = MAP.read_text(encoding="utf-8")
    for module in modules:
        if f'class="mod-path">{module}/' not in content:
            fail(f"map entry for {module!r} has no module path")

    print(f"ARCHITECTURE_MAP_OK modules={len(modules)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
