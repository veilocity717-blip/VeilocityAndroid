#!/usr/bin/env python3
"""Build offline-friendly JavaScript indexes for the Veilocity Portable Ark.

Place in the archive root alongside folders such as 'Arabic v056' or 'Ark Arabic v056'.
Run: python3 build_manifests.py
Existing index.html is preserved. Use --write-index to replace it.
No external Python packages or local HTTP server required.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

VERSION_RE = re.compile(r"\s+(v\d+)$", re.IGNORECASE)
ARCHIVE_FOLDER_RE = re.compile(r"^(?:ark\s+)?[^/]+\s+v\d+$", re.IGNORECASE)
FLAG_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg"}
HTML_EXTENSIONS = {".html", ".htm"}


def natural_key(value: str):
    return [int(x) if x.isdigit() else x.casefold() for x in re.split(r"(\d+)", value)]


def js_literal(value) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2)


def get_version(folder_name: str) -> str:
    match = VERSION_RE.search(folder_name)
    return match.group(1) if match else ""


def get_country(folder_name: str) -> str:
    country = re.sub(r"^ark\s+", "", folder_name, flags=re.IGNORECASE)
    country = VERSION_RE.sub("", country)
    return re.sub(r"\s+", " ", country.replace("_", " ")).strip()


def find_flag(files: list[str]) -> str:
    images = [name for name in files if Path(name).suffix.lower() in FLAG_EXTENSIONS]
    candidates = [name for name in images if "flag" in Path(name).stem.casefold()]
    return sorted(candidates, key=natural_key)[0] if candidates else ""


def choose_primary(files: list[str], country: str) -> str:
    html = [name for name in files if Path(name).suffix.lower() in HTML_EXTENSIONS]
    # Never use a court-related page as the landing page.
    html = [name for name in html if "court" not in Path(name).name.casefold()]
    if not html:
        return ""
    # Prefer an HTML page whose name matches the language/country, then stable sort.
    tokens = [t for t in re.split(r"[^\w]+", country.casefold()) if len(t) > 2]
    def priority(name: str):
        stem = Path(name).stem.casefold()
        return (0 if any(token in stem for token in tokens) else 1,
                len(Path(name).parts), natural_key(name))
    return sorted(html, key=priority)[0]


INDEX_HTML = '<!doctype html>\n<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">\n<title>Veilocity — Multilingual Archive</title>\n<style>\n:root{color-scheme:light;--peach:#eac3a1;--cream:#f8ead8;--paper:#fdf3e5;--ink:#51473c;--muted:#786958;--teal:#307b78;--line:#e9cfb4;--orange:#df683b}\n*{box-sizing:border-box}body{margin:0;min-height:100vh;background:linear-gradient(115deg,#e5b894 0%,#f3d2af 55%,#eac29e 100%);color:var(--ink);font:16px/1.55 system-ui,-apple-system,\'Segoe UI\',sans-serif}\n.shell{max-width:1300px;margin:26px auto;padding:26px;display:grid;grid-template-columns:250px minmax(0,1fr);gap:36px;background:rgba(252,240,224,.93);border:1px solid #f8e5d1;border-radius:22px;box-shadow:0 16px 42px #69492e25}\n.sidebar{display:flex;flex-direction:column;gap:15px}.brand-image{width:100%;aspect-ratio:1/1;object-fit:cover;border-radius:14px;box-shadow:0 6px 20px #55412e26}.brand-fallback{width:100%;aspect-ratio:1/1;display:grid;place-items:center;border-radius:14px;background:linear-gradient(145deg,#b7a59a,#e6c39c);font:700 1.9rem Georgia,serif;color:#285e5b;text-align:center}.side-note{background:#fff4e6;border:1px solid #efd5b9;border-radius:14px;padding:15px;color:#67594c;font-size:.92rem}.side-note strong{color:var(--orange)}\n.content{min-width:0}header{border-bottom:1px solid #ead9c7;padding:6px 0 20px;margin-bottom:22px}.eyebrow{text-transform:uppercase;letter-spacing:.18em;color:#b2a18e;font-size:.73rem}h1{color:var(--teal);font-size:clamp(2rem,4.5vw,3.1rem);font-weight:750;letter-spacing:.015em;margin:4px 0 8px}header p{margin:0;color:var(--muted)}\n.tools{display:flex;align-items:center;gap:15px;margin:0 0 23px;flex-wrap:wrap}input{background:#fffaf4;color:var(--ink);font:inherit;border:1px solid #e5d0b8;border-radius:12px;padding:14px 16px;min-width:220px;flex:1;outline-color:var(--teal)}#count{color:var(--muted);font-size:.9rem}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:17px}.card{background:#fff6eb;border:1px solid var(--line);border-radius:15px;padding:19px 13px 20px;text-align:center;box-shadow:0 4px 13px #73563a12;transition:transform .16s,box-shadow .16s}.card:hover{transform:translateY(-3px);box-shadow:0 9px 18px #73563a20}.flag-link{height:108px;display:flex;align-items:center;justify-content:center;border-radius:10px;text-decoration:none}.flag-link:focus-visible{outline:3px solid var(--teal)}.flag{max-width:155px;max-height:94px;object-fit:contain;filter:drop-shadow(0 4px 5px #0003)}.placeholder{font-size:3rem;color:var(--teal)}.country{color:var(--teal);font-weight:700;font-size:1.17rem;margin:10px 0 7px;overflow-wrap:anywhere}.version{display:inline-block;padding:3px 12px;background:#e6eeea;border-radius:30px;color:#426d66;font-size:.78rem;font-weight:700}.fallback{display:inline-block;margin-top:9px;color:var(--teal)}.empty{padding:30px;color:var(--muted);text-align:center}footer{font-size:.83rem;color:var(--muted);margin-top:40px;text-align:center}\n@media(max-width:760px){.shell{grid-template-columns:1fr;margin:12px;padding:18px;gap:20px}.sidebar{flex-direction:row;align-items:center}.brand-image,.brand-fallback{width:125px;min-width:125px;aspect-ratio:1}.side-note{flex:1}header{padding-top:0}.grid{grid-template-columns:repeat(auto-fill,minmax(145px,1fr))}}@media(max-width:430px){.sidebar{flex-direction:column}.brand-image,.brand-fallback{width:180px}h1{font-size:2rem}}\n.archive-subtitle{font-size:1rem;color:var(--muted);margin:0 0 18px;font-weight:600}.archive-description{max-width:68ch;margin:0 0 14px;line-height:1.65}.archive-remembrance{font-weight:750;color:var(--teal);margin:0 0 18px}.archive-testimony{background:#fff4e6;border-left:4px solid var(--teal);border-radius:0 12px 12px 0;padding:12px 17px;margin:0 0 20px;max-width:68ch}.archive-testimony p{margin:0;font-weight:650}.archive-testimony p+p{margin-top:3px}.archive-prompt{color:var(--muted);margin:0}\n</style><script src="archives.js"></script></head>\n<body><main class="shell"><aside class="sidebar"><div id="brand"><div class="brand-fallback">VEILOCITY</div></div><div class="side-note"><strong>VEILOCITY · ARKHIVE</strong><p>Multilingual portable archives for offline browsing. Select a flag to open its archive.</p></div></aside>\n<section class="content"><header><div class="eyebrow">VEILOCITY</div><h1>Veilocity — Multilingual Archive</h1><p class="archive-subtitle">Online Mirror + Patreon Frontend + Offline Archive</p><p class="archive-description">This repository is a multilingual archive of my social media writings, designed for preservation, accessibility, and redistribution.</p><p class="archive-remembrance">This is not entertainment — it’s remembrance.</p><div class="archive-testimony"><p>Inner order &gt; outer control.</p><p>I don’t perform — I testify.</p></div><p class="archive-prompt">Choose a language or country to explore the archive.</p></header><div class="tools"><input id="search" type="search" placeholder="Search country, language, folder or version…" aria-label="Search archives"><span id="count" aria-live="polite"></span></div><div id="grid" class="grid"></div><p id="empty" class="empty" hidden></p><footer>Portable archive · Local files · No server required on compatible desktop browsers</footer></section></main>\n<script>\n(()=>{\'use strict\';const config=window.VEILOCITY_ARCHIVES||{};const folders=config.archives||[];const grid=document.getElementById(\'grid\'),search=document.getElementById(\'search\'),count=document.getElementById(\'count\'),empty=document.getElementById(\'empty\'),items=[];\nconst path=(folder,file)=>[folder,...String(file||\'\').split(\'/\')].map(encodeURIComponent).join(\'/\');const version=f=>(f.match(/\\s+(v\\d+)$/i)||[])[1]||\'\';const country=f=>f.replace(/^ark\\s+/i,\'\').replace(/\\s+v\\d+$/i,\'\');\nif(config.logo){const img=document.createElement(\'img\');img.className=\'brand-image\';img.alt=\'Veilocity artwork\';img.src=path(config.logo.folder,config.logo.file);img.onerror=()=>img.remove();document.getElementById(\'brand\').replaceChildren(img);img.addEventListener(\'error\',()=>{document.getElementById(\'brand\').innerHTML=\'<div class="brand-fallback">VEILOCITY</div>\';});}\nfunction render(){const q=search.value.trim().toLocaleLowerCase();const filtered=items.filter(i=>i.search.includes(q));grid.replaceChildren();for(const i of filtered){const card=document.createElement(\'article\');card.className=\'card\';const a=document.createElement(\'a\');a.className=\'flag-link\';a.href=path(i.folder,i.primary);a.title=\'Open \'+i.name+\' archive\';if(i.flag){const img=document.createElement(\'img\');img.className=\'flag\';img.src=path(i.folder,i.flag);img.alt=i.name+\' flag\';img.onerror=()=>{img.replaceWith(Object.assign(document.createElement(\'span\'),{className:\'placeholder\',textContent:\'◈\'}));};a.append(img)}else{const span=document.createElement(\'span\');span.className=\'placeholder\';span.textContent=\'◈\';a.append(span)}const title=document.createElement(\'div\');title.className=\'country\';title.textContent=i.name;const pill=document.createElement(\'span\');pill.className=\'version\';pill.textContent=i.ver||\'Archive\';card.append(a,title,pill);if(!i.flag){const wrap=document.createElement(\'div\');const link=document.createElement(\'a\');link.className=\'fallback\';link.href=a.href;link.textContent=\'Open archive\';wrap.append(link);card.append(wrap)}grid.append(card)}count.textContent=filtered.length+\' of \'+items.length+\' archive\'+(items.length===1?\'\':\'s\');empty.hidden=filtered.length!==0;empty.textContent=folders.length===0?\'No archives found. Run build_manifests.py.\':items.length===0?\'No archive manifests loaded.\':\'No matching archives.\'}\nsearch.addEventListener(\'input\',render);render();for(const folder of folders){const s=document.createElement(\'script\');s.src=path(folder,\'manifest.js\');s.onload=()=>{const m=window.VEILOCITY_MANIFESTS?.[folder];if(m){const primary=m.primary_html||(m.files||[]).find(f=>/\\.html?$/i.test(f)&&!/court/i.test(f));if(primary){const name=m.country||country(folder),ver=m.version||version(folder);items.push({folder,name,ver,flag:m.flag||\'\',primary,search:[folder,name,ver,m.language,m.description,primary].join(\' \').toLocaleLowerCase()});items.sort((a,b)=>a.name.localeCompare(b.name))}}render()};s.onerror=render;document.head.append(s)}})();\n</script></body></html>\n'

def find_logo(root: Path, directories: list[Path]):
    # Prefer logo.png beside this script (the archive root).
    logo = root / "logo.png"
    if logo.is_file():
        return {"folder": ".", "file": "logo.png"}
    # An explicit image named veilocity-logo.* in the root takes precedence.
    extensions = FLAG_EXTENSIONS - {".svg"}
    for stem in ("veilocity-logo", "veilocity_logo", "veilocity"):
        for p in root.iterdir():
            if p.is_file() and p.stem.casefold() == stem and p.suffix.lower() in extensions:
                return {"folder": ".", "file": p.name}
    # Search the English archive for a graphic with a descriptive filename.
    for folder in sorted(directories, key=lambda d: ("english" not in d.name.casefold(), d.name.casefold())):
        candidates = []
        for p in folder.rglob("*"):
            if not p.is_file() or p.suffix.lower() not in extensions:
                continue
            name = p.stem.casefold()
            if "flag" in name:
                continue
            if any(word in name for word in ("veilocity", "logo", "qr")):
                priority = (0 if "veilocity" in name else 1 if "logo" in name else 2, len(p.parts))
                candidates.append((priority, p))
        if candidates:
            p = sorted(candidates, key=lambda x: x[0])[0][1]
            return {"folder": folder.name, "file": p.relative_to(folder).as_posix()}
    return None


LIBRARY_BUTTON = """<a id="veilocity-library-link" href="1_Library/index.html" style="display:block;max-width:440px;margin:0 0 12px;padding:13px 18px;border-radius:12px;background:#307b78;color:white;font:600 16px system-ui;text-align:center;text-decoration:none">Library for .pdfs</a>"""

def update_library(root: Path) -> None:
    library = root / "1_Library"
    if not library.is_dir():
        print("Library: 1_Library folder not found (create it beside the language folders).")
        return
    import html
    pdfs = sorted((p for p in library.rglob("*") if p.is_file() and p.suffix.lower() == ".pdf"), key=lambda p: natural_key(str(p)))
    from urllib.parse import quote
    links = "\n".join(f'<li><a href="{quote(p.relative_to(library).as_posix(), safe="/")}">{html.escape(p.relative_to(library).as_posix())}</a></li>' for p in pdfs)
    if not links: links = '<li>No PDF files found.</li>'
    page = ('<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Veilocity Library</title>'
            '<style>body{font:16px system-ui;background:#f8ead8;color:#51473c;padding:24px}main{max-width:860px;margin:auto}h1,a{color:#307b78}li{background:#fff6eb;border:1px solid #e9cfb4;border-radius:12px;padding:12px;margin:8px 0;list-style:none}ul{padding:0}</style>'
            '</head><body><main><a href="../index.html">← Back to archive</a><h1>Library</h1><p>Offline PDF collection</p><ul>'+links+'</ul></main></body></html>')
    (library / "index.html").write_text(page, encoding="utf-8")
    print(f"Library: indexed {len(pdfs)} PDF(s) in 1_Library/index.html")

def add_library_button(index_path: Path) -> None:
    import re
    content = index_path.read_text(encoding="utf-8")
    if 'id="veilocity-library-link"' in content: return
    # Insert above an existing Court of Illusions link if present; otherwise above search.
    court = re.search(r'<a\b[^>]*>[^<]*Court of Illusions[^<]*</a>', content, re.IGNORECASE)
    search = re.search(r'<div\s+class="tools"', content, re.IGNORECASE)
    if court: position = court.start()
    elif search: position = search.start()
    else:
        body = re.search(r'<body\b[^>]*>', content, re.IGNORECASE)
        position = body.end() if body else 0
    content = content[:position] + LIBRARY_BUTTON + "\n" + content[position:]
    index_path.write_text(content, encoding="utf-8")

def build(root: Path, write_index: bool = False) -> None:
    directories = sorted(
        (p for p in root.iterdir() if p.is_dir() and ARCHIVE_FOLDER_RE.match(p.name)),
        key=lambda p: natural_key(p.name),
    )
    included = []
    for directory in directories:
        # Recursive file listing supports asset subfolders, but the primary HTML
        # and flag may also be inside subfolders.
        files = sorted(
            (p.relative_to(directory).as_posix() for p in directory.rglob("*")
             if p.is_file() and p.name != "manifest.js"),
            key=natural_key,
        )
        country = get_country(directory.name)
        primary = choose_primary(files, country)
        if not primary:
            print(f"SKIP {directory.name}: no non-court HTML found")
            continue
        manifest = {
            "country": country,
            "language": country,
            "version": get_version(directory.name),
            "flag": find_flag(files),
            "primary_html": primary,
            "description": f"{country} portable archive",
            "item_count": len(files),
            "files": files,
        }
        js = (
            "window.VEILOCITY_MANIFESTS = window.VEILOCITY_MANIFESTS || {};\n"
            f"window.VEILOCITY_MANIFESTS[{json.dumps(directory.name, ensure_ascii=False)}] = "
            f"{js_literal(manifest)};\n"
        )
        (directory / "manifest.js").write_text(js, encoding="utf-8")
        included.append(directory.name)
        print(f"OK   {directory.name}: {primary} | flag: {manifest['flag'] or 'none'}")

    archives_js = "window.VEILOCITY_ARCHIVES = " + js_literal({"archives": included, "logo": find_logo(root, directories)}) + ";\n"
    (root / "archives.js").write_text(archives_js, encoding="utf-8")
    index_path = root / "index.html"
    if write_index or not index_path.exists():
        index_path.write_text(INDEX_HTML, encoding="utf-8")
        print("Wrote index.html (default offline landing page).")
    else:
        print("Preserved existing index.html (design unchanged).")
    update_library(root)
    add_library_button(index_path)
    print(f"\nWrote archives.js with {len(included)} archive(s).")
    if not included:
        print("No eligible archive folders found. Expected e.g. Arabic v056 or Ark Arabic v056, containing a non-court HTML file.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent,
                        help="Directory containing archive folders (default: script directory)")
    parser.add_argument("--write-index", action="store_true",
                        help="Overwrite index.html with the English-inspired peach and cream design")
    args = parser.parse_args()
    build(args.root.expanduser().resolve(), write_index=args.write_index)
