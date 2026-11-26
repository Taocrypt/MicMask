#!/usr/bin/env python3
"""从 site/index.html 生成英文版 en.html（部署时运行，线上地址 /en）。

只改 <head> 里搜索引擎直接读的部分（lang、title、description、og/twitter、canonical），
正文仍由页面里的 i18n 脚本按地址切成英文。英文文案取自 index.html 里的 EN 字典，不另外维护。
用法：python3 tools/site_en.py site/index.html out/en.html
"""
import html, json, re, sys

SITE = "https://micmask.taocrypt.com"

def en_str(src, key):
    m = re.search(r'^\s*"' + re.escape(key) + r'":\s*("(?:[^"\\]|\\.)*")', src, re.M)
    if not m:
        sys.exit(f"site_en: EN 字典里找不到 {key}")
    # EN 字典的值是 HTML 片段：先还原实体，再按属性/文本转义
    return html.unescape(json.loads(m.group(1)))

def sub1(pat, repl, s):
    s2, n = re.subn(pat, lambda m: repl, s, count=1)
    if n != 1:
        sys.exit(f"site_en: 没匹配到 {pat}")
    return s2

def main(src_path, dst_path):
    s = open(src_path, encoding="utf-8").read()
    head_end = s.index("</head>")
    head, rest = s[:head_end], s[head_end:]
    a = lambda k: html.escape(en_str(s, k), quote=True)
    title, desc = a("meta.title"), a("meta.desc")
    og_title, og_desc = a("meta.ogTitle"), a("meta.ogDesc")

    head = sub1(r'<html lang="zh-CN">', '<html lang="en">', head)
    head, n = re.subn(r'(<title[^>]*>)[^<]*(</title>)', lambda m: m.group(1) + title + m.group(2), head, count=1)
    if n != 1:
        sys.exit("site_en: 找不到 <title>")
    head = sub1(r'<link rel="canonical" href="[^"]*">', f'<link rel="canonical" href="{SITE}/en">', head)
    head = sub1(r'<meta property="og:url" content="[^"]*">', f'<meta property="og:url" content="{SITE}/en">', head)
    head = sub1(r'<meta property="og:locale" content="zh_CN"', '<meta property="og:locale" content="en_US"', head)
    head = sub1(r'<meta property="og:locale:alternate" content="en_US">', '<meta property="og:locale:alternate" content="zh_CN">', head)

    def meta(attr_sel, val, h):
        # 把带有 attr_sel 的那个 meta 标签的 content 换掉（属性顺序不固定）
        pat = r'<meta\b[^>]*' + re.escape(attr_sel) + r'[^>]*>'
        m = re.search(pat, h)
        if not m:
            sys.exit(f"site_en: 找不到 meta {attr_sel}")
        tag = re.sub(r'content="[^"]*"', 'content="' + val + '"', m.group(0), count=1)
        return h[:m.start()] + tag + h[m.end():]

    head = meta('name="description"', desc, head)
    head = meta('property="og:title"', og_title, head)
    head = meta('property="og:description"', og_desc, head)
    head = meta('name="twitter:title"', og_title, head)
    head = meta('name="twitter:description"', og_desc, head)
    open(dst_path, "w", encoding="utf-8").write(head + rest)

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
