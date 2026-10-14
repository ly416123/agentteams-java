#!/usr/bin/env python3
"""Deterministic technical checks for reader-facing PDF artifacts.

Visual polish, semantic completeness, topical relevance and factual accuracy
are recorded in the companion human/judge rubric; they cannot be established by
PDF structure checks alone.
"""

from __future__ import annotations

import re
from collections import Counter
from typing import Any


_CJK_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
_STANDARD_14 = {
    "courier", "courier-bold", "courier-oblique", "courier-boldoblique",
    "helvetica", "helvetica-bold", "helvetica-oblique", "helvetica-boldoblique",
    "times-roman", "times-bold", "times-italic", "times-bolditalic",
    "symbol", "zapfdingbats",
}


def inspect_pdf(data: bytes, *, minimum_cjk_ratio: float = 0.25,
                minimum_font_size: float = 7.5, maximum_font_size: float = 36.0,
                minimum_margin_points: float = 12.0) -> dict[str, Any]:
    """Inspect PDF text, font resources, glyph extraction, and page geometry."""
    try:
        import fitz
    except ImportError as exc:  # pragma: no cover - environment-specific
        raise RuntimeError("PyMuPDF is required for PDF quality inspection") from exc

    failures: list[str] = []
    try:
        document = fitz.open(stream=data, filetype="pdf")
    except Exception as exc:
        return {
            "technicalStatus": "FAILED",
            "failureCodes": ["unparseable_pdf"],
            "errorType": type(exc).__name__,
            "pageCount": 0,
            "blankPageCount": 0,
            "textCharacterCount": 0,
            "cjkCharacterCount": 0,
            "cjkCharacterRatio": 0.0,
            "replacementCharacterCount": 0,
            "outOfPageSpanCount": 0,
            "fontUsage": {"fonts": [], "fontSizeCounts": {},
                          "embeddedNonstandardFontCount": 0,
                          "nonEmbeddedNonstandardFonts": []},
            "layout": {"tightMarginSpanCount": 0, "minimumMarginPoints": minimum_margin_points,
                       "pages": []},
            "visualReviewRequired": True,
            "contentReviewRequired": True,
        }
    try:
        pages: list[dict[str, Any]] = []
        extracted: list[str] = []
        font_sizes: list[float] = []
        fonts: dict[tuple[int, str], dict[str, Any]] = {}
        out_of_page_spans = 0
        tight_margin_spans = 0
        blank_pages = 0
        for page_number, page in enumerate(document, start=1):
            text_dict = page.get_text("dict")
            page_text: list[str] = []
            page_spans = 0
            for block in text_dict.get("blocks", []):
                for line in block.get("lines", []):
                    for span in line.get("spans", []):
                        text = str(span.get("text", ""))
                        if not text.strip():
                            continue
                        page_text.append(text)
                        page_spans += 1
                        size = float(span.get("size", 0.0))
                        font_sizes.append(size)
                        x0, y0, x1, y1 = map(float, span.get("bbox", (0, 0, 0, 0)))
                        if x0 < 0 or y0 < 0 or x1 > page.rect.width or y1 > page.rect.height:
                            out_of_page_spans += 1
                        if min(x0, y0, page.rect.width - x1, page.rect.height - y1) < minimum_margin_points:
                            tight_margin_spans += 1
            text = "".join(page_text)
            if not text.strip():
                blank_pages += 1
            extracted.append(text)
            fonts_on_page = page.get_fonts(full=True)
            for font in fonts_on_page:
                xref, _ext, font_type, base_name, resource_name, *_ = font
                key = (int(xref), str(base_name or resource_name))
                if key not in fonts:
                    try:
                        _name, _extension, _kind, _content = document.extract_font(int(xref))
                        embedded = bool(_content)
                    except Exception:
                        embedded = False
                    normalized_name = re.sub(r"^[A-Z]{6}\+", "", str(base_name or "")).lower()
                    is_standard = normalized_name in _STANDARD_14
                    fonts[key] = {"name": str(base_name or resource_name),
                                  "type": str(font_type), "embedded": embedded,
                                  "standard14": is_standard}
            pages.append({"page": page_number, "textCharacterCount": len(text),
                          "spanCount": page_spans, "widthPoints": round(page.rect.width, 2),
                          "heightPoints": round(page.rect.height, 2),
                          "textDensity": round(len(text) / max(page.rect.width * page.rect.height, 1), 6)})

        full_text = "\n".join(extracted)
        visible_chars = [char for char in full_text if not char.isspace()]
        cjk_count = len(_CJK_RE.findall(full_text))
        cjk_ratio = cjk_count / max(len(visible_chars), 1)
        replacement_count = full_text.count("\ufffd") + full_text.count("\u25a1")
        font_counts = Counter(round(size, 1) for size in font_sizes)
        non_embedded = [font for font in fonts.values() if not font["embedded"] and not font["standard14"]]
        min_size = min(font_sizes) if font_sizes else None
        max_size = max(font_sizes) if font_sizes else None
        if not full_text.strip():
            failures.append("no_extractable_text")
        if not document.page_count:
            failures.append("no_pages")
        if blank_pages:
            failures.append("blank_page")
        if cjk_ratio < minimum_cjk_ratio:
            failures.append("insufficient_chinese_text")
        if replacement_count:
            failures.append("replacement_glyphs")
        if out_of_page_spans:
            failures.append("text_outside_page")
        if min_size is not None and (min_size < minimum_font_size or max_size > maximum_font_size):
            failures.append("font_size_out_of_range")
        if non_embedded:
            failures.append("non_embedded_nonstandard_font")

        return {
            "technicalStatus": "FAILED" if failures else "PASSED",
            "failureCodes": failures,
            "pageCount": document.page_count,
            "blankPageCount": blank_pages,
            "textCharacterCount": len(full_text),
            "cjkCharacterCount": cjk_count,
            "cjkCharacterRatio": round(cjk_ratio, 4),
            "replacementCharacterCount": replacement_count,
            "outOfPageSpanCount": out_of_page_spans,
            "tightMarginSpanCount": tight_margin_spans,
            "fontUsage": {
                "fonts": list(fonts.values()),
                "fontSizeCounts": dict(sorted(font_counts.items())),
                "minimumFontSize": round(min_size, 2) if min_size is not None else None,
                "maximumFontSize": round(max_size, 2) if max_size is not None else None,
                "embeddedNonstandardFontCount": sum(font["embedded"] and not font["standard14"]
                                                     for font in fonts.values()),
                "nonEmbeddedNonstandardFonts": non_embedded,
            },
            "layout": {
                "tightMarginSpanCount": tight_margin_spans,
                "minimumMarginPoints": minimum_margin_points,
                "pages": pages,
            },
            "visualReviewRequired": True,
            "contentReviewRequired": True,
        }
    finally:
        document.close()
