#!/usr/bin/env python3
"""检查界面翻译：扫描源码里所有 tr("…") 字面量键，对比 app/src/main/assets/i18n/en.json。

用法：python3 tools/i18n_check.py [--dump keys.json]
报告 en.json 缺失的键（必须为 0）、多余的键、占位符不一致的条目；有缺失或占位符不一致时退出码 1。

除 tr("…") 字面量外，还收集运行时才传给 tr() 的「数据型」中文键：
声线/场景预设名（VoicePreset / cardPresets）、效果旋钮名（FxCtl）、TTS 语气名（RefFeatureStore.LABELS）、
通知渠道名（CH_NAME）以及 DYNAMIC 里手工列出的键。
"""
import json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'app/src/main/kotlin')
EN = os.path.join(ROOT, 'app/src/main/assets/i18n/en.json')

LIT = r'"((?:[^"\\\n]|\\.)*)"'
TR = re.compile(r'(?<![\w.])tr\(\s*' + LIT)
DATA = [
    re.compile(r'\bVoicePreset\(\s*' + LIT),
    re.compile(r'\bFxCtl\(\s*' + LIT),
    re.compile(r'^\s*' + LIT + r'\s+to\s+fx\(', re.M),               # cardPresets
    re.compile(r'\bCH_NAME\s*=\s*' + LIT),
]
LABELS = re.compile(r'val LABELS\s*=\s*mapOf\((.*?)\n\s*\)', re.S)
DYNAMIC = ['自定义']  # fxPreset 的特殊值，显示时 tr(st.fxPreset)
HAN = re.compile(r'[\u4e00-\u9fff]')


def unescape(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == '\\' and i + 1 < len(s):
            n = s[i + 1]
            if n == 'u':
                out.append(chr(int(s[i + 2:i + 6], 16))); i += 6; continue
            out.append({'n': '\n', 't': '\t', 'r': '\r', 'b': '\b', '"': '"', "'": "'", '\\': '\\', '$': '$'}.get(n, n))
            i += 2; continue
        out.append(c); i += 1
    return ''.join(out)


def collect():
    keys = {}
    for dp, _, fns in os.walk(SRC):
        for fn in fns:
            if not fn.endswith('.kt'):
                continue
            p = os.path.join(dp, fn)
            src = open(p, encoding='utf-8').read()
            rel = os.path.relpath(p, ROOT)
            for m in TR.finditer(src):
                keys.setdefault(unescape(m.group(1)), rel)
            for rx in DATA:
                for m in rx.finditer(src):
                    k = unescape(m.group(1))
                    if HAN.search(k):
                        keys.setdefault(k, rel)
            for m in LABELS.finditer(src):
                for v in re.findall(r'to\s+' + LIT, m.group(1)):
                    keys.setdefault(unescape(v), rel)
    for k in DYNAMIC:
        keys.setdefault(k, '(dynamic)')
    return keys


def ph(s):
    return sorted(re.findall(r'\{\d+\}', s)), sorted(re.findall(r'%[-+ 0#]*\d*(?:\.\d+)?[a-zA-Z%]', s))


def main():
    keys = collect()
    if '--dump' in sys.argv:
        out = sys.argv[sys.argv.index('--dump') + 1]
        json.dump(keys, open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    en = json.load(open(EN, encoding='utf-8')) if os.path.exists(EN) else {}
    missing = [k for k in keys if k not in en or not str(en[k]).strip()]
    extra = [k for k in en if k not in keys]
    bad = [k for k in keys if k in en and ph(k) != ph(en[k])]
    print(f'tr keys: {len(keys)}   en.json entries: {len(en)}')
    print(f'missing: {len(missing)}   extra: {len(extra)}   placeholder mismatch: {len(bad)}')
    for k in missing:
        print('  MISSING', json.dumps(k, ensure_ascii=False), '←', keys[k])
    for k in extra:
        print('  EXTRA  ', json.dumps(k, ensure_ascii=False))
    for k in bad:
        print('  PH     ', json.dumps(k, ensure_ascii=False), '→', json.dumps(en[k], ensure_ascii=False))
    sys.exit(1 if missing or bad else 0)


if __name__ == '__main__':
    main()
