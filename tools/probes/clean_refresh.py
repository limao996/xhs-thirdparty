import re, io, sys, pathlib

# 仓库根 = tools/probes/<this file> 的上两级；脚本随仓库移动而无需改路径。
REPO = pathlib.Path(__file__).resolve().parents[2]

BASE = REPO / 'app/src/main/java/com/thirdparty/xhs'

# 1) drop the now-uncalled refresh() (docs + body) — the pull-to-refresh caller is gone
TARGETS = {
    'ui/viewmodel/AuthorViewModel.kt': 'AuthorViewModel',
    'ui/viewmodel/LocalListViewModel.kt': 'LocalListViewModel',
    'ui/viewmodel/SearchViewModel.kt': 'SearchViewModel',
}

def strip_refresh(text):
    # find the doc comment + fun refresh() { ... } at indentation 4
    m = re.search(r'\n(?P<ind>[ ]{4})(/\*\*(?:(?!\*/).)*?\*/\n)?[ ]{4}fun refresh\(\) \{', text, re.S)
    if not m:
        return text, False
    start = m.start() + 1
    # walk braces from the opening brace of the function
    i = text.index('{', m.end() - 1)
    depth = 0
    while i < len(text):
        if text[i] == '{':
            depth += 1
        elif text[i] == '}':
            depth -= 1
            if depth == 0:
                break
        i += 1
    end = i + 1
    # swallow the trailing newline
    while end < len(text) and text[end] in '\r\n':
        end += 1
    return text[:start] + text[end:], True

for rel in TARGETS:
    p = BASE / rel
    src = p.read_text(encoding='utf-8')
    out, did = strip_refresh(src)
    if did:
        p.write_text(out, encoding='utf-8')
        print('  removed refresh() from', rel)
    else:
        print('  !! refresh() not found in', rel)

# 2) drop the `refreshing` field and every write to it
ALL = [
    'ui/viewmodel/AuthorViewModel.kt',
    'ui/viewmodel/LocalListViewModel.kt',
    'ui/viewmodel/SearchViewModel.kt',
    'ui/viewmodel/DiscoverViewModel.kt',
]
for rel in ALL:
    p = BASE / rel
    src = p.read_text(encoding='utf-8')
    out = re.sub(r'\n[ ]*val refreshing: Boolean = false,', '', src)
    out = re.sub(r'\n[ ]*refreshing = (?:true|false),', '', out)
    out = re.sub(r'refreshing = (?:true|false), ?', '', out)
    out = re.sub(r', refreshing = (?:true|false)', '', out)
    if out != src:
        p.write_text(out, encoding='utf-8')
        print('  cleaned refreshing from', rel)

# 3) report anything left
left = []
for p in BASE.rglob('*.kt'):
    t = p.read_text(encoding='utf-8')
    if 'refreshing' in t or 'PullToRefresh' in t:
        for i, line in enumerate(t.splitlines(), 1):
            if 'refreshing' in line or 'PullToRefresh' in line:
                left.append('%s:%d  %s' % (p.name, i, line.strip()))
print('\n=== remaining ===')
print('\n'.join(left) if left else '  (none)')
