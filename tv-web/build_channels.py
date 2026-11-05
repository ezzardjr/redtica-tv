#!/usr/bin/env python3
"""Genera src/channels.js desde app/src/main/assets/orden.txt (mismo orden y números que Android)."""
import json, os
here = os.path.dirname(os.path.abspath(__file__))
orden = os.path.join(here, '..', 'app', 'src', 'main', 'assets', 'orden.txt')
out = []
for line in open(orden, encoding='utf-8'):
    line = line.strip()
    if not line or line.startswith('#'):
        continue
    n, cid, name = line.split('|', 2)
    out.append({'n': int(n), 'id': cid.strip(), 'name': name.strip()})
out.sort(key=lambda c: c['n'])
js = ('// Generado desde app/src/main/assets/orden.txt — no editar a mano.\n'
      'var REDTICA_CHANNELS = ' + json.dumps(out, ensure_ascii=False, indent=0) + ';\n')
open(os.path.join(here, 'src', 'channels.js'), 'w', encoding='utf-8').write(js)
print(len(out), 'canales')
