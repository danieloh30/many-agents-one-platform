"""Build the static documentation site for GitHub Pages.

README.md -> index.html, docs/**/*.md -> docs/**/*.html. Each page embeds its Markdown source and
renders it in the browser with marked; ```mermaid blocks are rendered by mermaid. Both libraries
are pinned (exact version + Subresource Integrity) in template.html. Standard library only.

Relative links: to a published .md page -> the generated .html page; to any other repository file
-> that file on GitHub (blob view at the built commit).

Usage: python3 -I .github/pages/build_site.py <repo-root> <output-dir>
Env: GITHUB_SERVER_URL, GITHUB_REPOSITORY, GITHUB_SHA (for links to repository files).
"""

import html
import os
import posixpath
import re
import sys
from pathlib import Path

LINK = re.compile(r"(\]\()([^)\s]+)((?:\s+\"[^\"]*\")?\))")


def main() -> int:
    root = Path(sys.argv[1]).resolve()
    out = Path(sys.argv[2]).resolve()
    template = (Path(__file__).parent / "template.html").read_text(encoding="utf-8")

    sources = []
    if (root / "README.md").is_file():
        sources.append("README.md")
    sources += sorted(p.relative_to(root).as_posix() for p in (root / "docs").rglob("*.md"))
    published = {s: ("index.html" if s == "README.md" else s[:-3] + ".html") for s in sources}

    server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    ref = os.environ.get("GITHUB_SHA", "main")
    blob_base = f"{server}/{repo}/blob/{ref}/" if repo else None

    for src in sources:
        page = published[src]
        src_dir = posixpath.dirname(src)
        page_dir = posixpath.dirname(page)

        def rewrite(m: re.Match) -> str:
            target = m.group(2)
            if re.match(r"^([a-z][a-z0-9+.-]*:|#|/)", target, re.I):
                return m.group(0)
            path, _, frag = target.partition("#")
            resolved = posixpath.normpath(posixpath.join(src_dir, path)) if path else src
            if resolved in published:
                new = posixpath.relpath(published[resolved], page_dir or ".")
            elif blob_base and not resolved.startswith(".."):
                new = blob_base + resolved
            else:
                return m.group(0)
            return f"{m.group(1)}{new}{'#' + frag if frag else ''}{m.group(3)}"

        text = (root / src).read_text(encoding="utf-8")
        text = LINK.sub(rewrite, text)
        title_match = re.search(r"^#\s+(.+)$", text, re.M)
        title = title_match.group(1).strip() if title_match else src
        home = posixpath.relpath("index.html", page_dir or ".")
        nav = "".join(
            f'<a href="{html.escape(posixpath.relpath(p, page_dir or "."))}">'
            f'{html.escape("Overview" if s == "README.md" else posixpath.basename(s)[:-3])}</a>'
            for s, p in published.items()
            if not re.match(r"docs/decisions/\d", s)  # ADRs are reached via docs/decisions/README
        )
        body = (
            template.replace("{{TITLE}}", html.escape(title))
            .replace("{{HOME}}", html.escape(home))
            .replace("{{NAV}}", nav)
            # Keep the embedded source from closing its <script> element.
            .replace("{{MARKDOWN}}", text.replace("</script", "<\\/script"))
        )
        dest = out / page
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(body, encoding="utf-8")
        print(f"{src} -> {page}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
