"""Build the GitHub Pages manual: site/content/{ja,en}/*.md -> site/_site.

Japanese pages live at the site root, English pages under /en/. Front matter
(between --- lines) supplies `title` and `description`. Usage:
    python site/build.py [--out DIR]
Requires the `markdown` package.
"""
from __future__ import annotations

import argparse
import html
import re
import shutil
from pathlib import Path

import markdown

SITE = Path(__file__).resolve().parent
REPO = "https://github.com/kmizu/stablediffusion-nui"
RELEASES = REPO + "/releases/latest"

# Page order and sidebar groups. Slugs are shared by both languages.
NAV = [
    ("start", ["index", "install", "first-image"]),
    ("guide", ["generate", "controlnet", "lora-embeddings", "extensions", "tools", "image-browser", "character-searcher"]),
    ("advanced", ["mcp", "settings", "localization", "troubleshooting", "releases"]),
]
GROUPS = {
    "ja": {"start": "はじめに", "guide": "使い方", "advanced": "さらに"},
    "en": {"start": "Getting started", "guide": "Guide", "advanced": "More"},
}
TEXT = {
    "ja": {"manual": "マニュアル", "download": "ダウンロード", "other": "English", "toc": "このページの内容",
           "prev": "前へ", "next": "次へ", "menu": "目次", "edit": "このページを GitHub で編集",
           "licence": "Stable Diffusion NUI は AGPL-3.0 で配布されています。"},
    "en": {"manual": "Manual", "download": "Download", "other": "日本語", "toc": "On this page",
           "prev": "Previous", "next": "Next", "menu": "Contents", "edit": "Edit this page on GitHub",
           "licence": "Stable Diffusion NUI is distributed under AGPL-3.0."},
}


def front_matter(text: str) -> tuple[dict, str]:
    meta = {}
    if text.startswith("---\n"):
        head, _, text = text[4:].partition("\n---\n")
        for line in head.splitlines():
            key, _, value = line.partition(":")
            if key.strip():
                meta[key.strip()] = value.strip()
    return meta, text


def page_url(lang: str, slug: str, root: str) -> str:
    base = root + ("en/" if lang == "en" else "")
    return base + ("" if slug == "index" else slug + ".html")


def render(lang: str, slug: str, pages: dict, layout: str, out: Path):
    meta, body = pages[lang][slug]
    depth = 1 if lang == "en" else 0
    root = "../" * depth
    md = markdown.Markdown(extensions=["tables", "fenced_code", "attr_list", "admonition", "md_in_html", "sane_lists",
                                       "toc"],
                           extension_configs={"toc": {"permalink": "#", "permalink_title": "", "toc_depth": "2-3"}})
    content = md.convert(body)
    # Authors write shots/x.png; serve from the shared assets folder.
    content = re.sub(r'(src|href)="shots/', rf'\1="{root}assets/shots/', content)
    # Wrap screenshots in figures with their alt text as caption.
    content = re.sub(r'<p><img alt="([^"]*)" src="([^"]+)" ?/?></p>',
                     lambda m: f'<figure class="shot"><img src="{m.group(2)}" alt="{m.group(1)}" loading="lazy">'
                               f'<figcaption>{m.group(1)}</figcaption></figure>', content)
    t = TEXT[lang]
    order = [s for _, slugs in NAV for s in slugs]
    nav_html = []
    current = ' aria-current="page"'
    for group, slugs in NAV:
        items = "".join(
            f'<li><a href="{page_url(lang, s, root)}"{current if s == slug else ""}>'
            f'{html.escape(pages[lang][s][0].get("nav", pages[lang][s][0]["title"]))}</a></li>'
            for s in slugs if s in pages[lang])
        nav_html.append(f'<p class="nav-group">{GROUPS[lang][group]}</p><ul>{items}</ul>')
    toc_items = "".join(
        f'<li class="toc-{tok["level"]}"><a href="#{tok["id"]}">{tok["name"]}</a></li>'
        for top in md.toc_tokens for tok in [top, *top.get("children", [])])
    i = order.index(slug)
    prev_s = order[i - 1] if i > 0 else None
    next_s = order[i + 1] if i + 1 < len(order) else None
    pager = '<nav class="pager">'
    if prev_s:
        pager += (f'<a class="prev" href="{page_url(lang, prev_s, root)}"><span>{t["prev"]}</span>'
                  f'{html.escape(pages[lang][prev_s][0]["title"])}</a>')
    if next_s:
        pager += (f'<a class="next" href="{page_url(lang, next_s, root)}"><span>{t["next"]}</span>'
                  f'{html.escape(pages[lang][next_s][0]["title"])}</a>')
    pager += "</nav>"
    other = "en" if lang == "ja" else "ja"
    other_url = page_url(other, slug, root)
    title = meta["title"] if slug != "index" else "Stable Diffusion NUI"
    full_title = f"{meta['title']} | Stable Diffusion NUI {t['manual']}" if slug != "index" else f"Stable Diffusion NUI {t['manual']}"
    hero = ""
    if slug == "index":
        hero = (SITE / "templates" / f"hero.{lang}.html").read_text(encoding="utf-8").replace("{root}", root) \
            .replace("{releases}", RELEASES)
    page = layout.format(
        lang=lang, lang_home="en/" if lang == "en" else "", title=html.escape(full_title), description=html.escape(meta.get("description", "")), root=root,
        manual=t["manual"], download=t["download"], releases=RELEASES, repo=REPO,
        other_url=other_url, other_lang=other, other_label=t["other"], menu=t["menu"],
        nav="".join(nav_html), toc_label=t["toc"], toc=toc_items, hero=hero,
        page_class="home" if slug == "index" else "doc",
        heading="" if slug == "index" else f"<h1>{html.escape(title)}</h1>",
        content=content, pager=pager, licence=t["licence"], edit_label=t["edit"],
        edit_url=f"{REPO}/edit/main/site/content/{lang}/{slug}.md")
    target = out / ("en" if lang == "en" else "") / ("index.html" if slug == "index" else slug + ".html")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(page, encoding="utf-8", newline="\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=SITE / "_site")
    out = parser.parse_args().out
    # Empty rather than remove the folder, so a local preview server can keep serving it.
    out.mkdir(parents=True, exist_ok=True)
    for child in out.iterdir():
        shutil.rmtree(child) if child.is_dir() else child.unlink()
    shutil.copytree(SITE / "assets", out / "assets")
    layout = (SITE / "templates" / "layout.html").read_text(encoding="utf-8")
    pages = {}
    for lang in ("ja", "en"):
        pages[lang] = {}
        for path in sorted((SITE / "content" / lang).glob("*.md")):
            pages[lang][path.stem] = front_matter(path.read_text(encoding="utf-8"))
    order = [s for _, slugs in NAV for s in slugs]
    for lang in ("ja", "en"):
        missing = [s for s in order if s not in pages[lang]]
        if missing:
            raise SystemExit(f"{lang}: missing pages {missing}")
        for slug in order:
            render(lang, slug, pages, layout, out)
    missing_assets = sorted({(page.relative_to(out).as_posix(), ref)
                             for page in out.rglob("*.html")
                             for ref in re.findall(r'(?:src|href)="((?:\.\./)*assets/[^"#?]+)"', page.read_text(encoding="utf-8"))
                             if not (page.parent / ref).resolve().is_file()})
    if missing_assets:
        raise SystemExit("missing assets:\n" + "\n".join(f"  {p}: {r}" for p, r in missing_assets))
    (out / ".nojekyll").write_text("", encoding="utf-8")
    print(f"built {sum(len(p) for p in pages.values())} pages into {out}")


if __name__ == "__main__":
    main()
