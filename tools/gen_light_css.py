#!/usr/bin/env python3
"""Regenerates the light theme (Pearl Silver) CSS block inside app/src/main/assets/index.html.

Every rule in the page's <style> that holds a hard-coded dark-theme color gets a twin under `body.light`
with the color mapped to a light one. Run it again after changing colors in the dark CSS:
    python3 tools/gen_light_css.py
Hand-made light fixes live in the block between the MANUAL markers and are not touched."""
import re, sys, os
P = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'index.html')
GS, GE = '/* LIGHT-GEN-START */', '/* LIGHT-GEN-END */'

def lum(r, g, b): return .299 * r + .587 * g + .114 * b
def hx(v): return '#%02x%02x%02x' % tuple(max(0, min(255, int(round(x)))) for x in v)
def parse_hex(h):
    h = h.lstrip('#')
    if len(h) == 3: h = ''.join(c * 2 for c in h)
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
def sat(r, g, b): return max(r, g, b) - min(r, g, b)

SPECIAL = {  # exact dark colors with a hand-picked light twin
    '#1b2a3f': 'rgba(var(--ar),.13)', '#2f4c73': 'rgba(var(--ar),.38)', '#cfe2f8': 'var(--accent)',
    '#2a1517': '#fdecea', '#5a2a2e': '#f0b9b4', '#f0867f': '#c0392b',
    '#3a2a12': '#fff0d2', '#ffb44d': '#a35f00', '#3d1614': '#fde4e1', '#ff6b5e': '#c0392b',
    '#2fe0a2': '#12875c', '#ff5d79': '#d12b4d', '#7fd6a4': '#2f9e66', '#f5a524': '#c27b00',
    '#ffd58a': '#8a5200', '#9bd13d': '#3d6b00', '#e8c76a': '#7a5b00', '#8a8d92': '#7a7f87',
}

def map_color(tok, prop, in_shadow):
    t = tok.lower()
    if t in SPECIAL: return SPECIAL[t]
    if t.startswith('#'):
        r, g, b = parse_hex(t); L = lum(r, g, b)
        if t in ('#fff', '#ffffff'): return tok
        if sat(r, g, b) > 40: return tok                   # colored (brand, status) colors stay
        if prop.startswith('background'):
            if L < 90: v = 255 - L * .6; return hx((v - 1, v, v + 2))
            return tok
        if prop.startswith('border') or prop in ('outline', 'stroke'):
            if L < 90: v = 252 - L * 1.1; return hx((v, v + 1, v + 3))
            return tok
        if prop in ('color', 'fill', 'text-shadow', 'caret-color'):
            if L > 100: v = min(100, (255 - L) * .8 + 6); return hx((v, v + 1, v + 4))
            if prop == 'text-shadow': return 'rgba(255,255,255,.7)'
            return tok
        if in_shadow: return 'rgba(40,50,70,.18)'
        return tok
    m = re.match(r'rgba?\(([^)]*)\)$', t)
    if m:
        parts = [x.strip() for x in m.group(1).split(',')]
        if 'var(' in m.group(1): return tok
        try: r, g, b = [float(x) for x in parts[:3]]
        except ValueError: return tok
        a = float(parts[3]) if len(parts) > 3 else 1.0
        if (r, g, b) == (255, 255, 255):
            if in_shadow: return 'rgba(255,255,255,.9)'
            if prop.startswith('background'): return 'rgba(19,21,25,%.2f)' % min(.35, a * 1.0)
            return 'rgba(19,21,25,%.2f)' % min(.5, a * 1.4)
        if (r, g, b) == (0, 0, 0):
            if in_shadow: return 'rgba(40,50,70,%.2f)' % min(.22, a * .35)
            return tok
        if lum(r, g, b) < 60 and sat(int(r), int(g), int(b)) < 20:   # dark glass
            if prop.startswith('background'): return 'rgba(255,255,255,%.2f)' % min(.9, a)
            if in_shadow: return 'rgba(40,50,70,.18)'
        return tok
    return tok

COLOR = re.compile(r'#[0-9a-fA-F]{3,6}\b|rgba?\([^()]*\)')

def map_decl(prop, val):
    in_shadow = prop in ('box-shadow', 'text-shadow')
    out = COLOR.sub(lambda m: map_color(m.group(0), prop, in_shadow), val)
    return out

def split_decls(body):
    out, depth, cur = [], 0, ''
    for ch in body:
        if ch == '(': depth += 1
        if ch == ')': depth -= 1
        if ch == ';' and depth == 0: out.append(cur); cur = ''
        else: cur += ch
    if cur.strip(): out.append(cur)
    return out

def main():
    s = open(P, encoding='utf-8').read()
    if GS in s:
        a = s.index(GS); b = s.index(GE) + len(GE)
        s = s[:a].rstrip() + '\n' + s[b:].lstrip('\n')
    i0 = s.index('<style>') + 7; i1 = s.index('</style>')
    css = s[i0:i1]
    css_nc = re.sub(r'/\*.*?\*/', '', css, flags=re.S)
    css_nc = re.sub(r'@font-face\s*\{[^{}]*\}', '', css_nc)
    rules = []
    for m in re.finditer(r'([^{}]+)\{([^{}]*)\}', css_nc):
        sel = m.group(1).strip(); body = m.group(2)
        if sel.startswith(':root') or sel.startswith('html') or sel == 'body' or 'body.light' in sel: continue
        decls = []
        for d in split_decls(body):
            if ':' not in d: continue
            prop, val = d.split(':', 1); prop = prop.strip(); val = val.strip()
            nv = map_decl(prop, val)
            if nv != val: decls.append('%s: %s' % (prop, nv))
        if decls:
            sels = ', '.join('body.light ' + x.strip() for x in sel.split(','))
            rules.append('  %s { %s; }' % (sels, '; '.join(decls)))
    block = '  %s\n%s\n  %s\n' % (GS, '\n'.join(rules), GE)
    s = s[:s.index('</style>')] + block + s[s.index('</style>'):]
    open(P, 'w', encoding='utf-8').write(s)
    print('light rules:', len(rules))
main()
