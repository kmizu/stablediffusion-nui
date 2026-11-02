import hashlib, subprocess, sys, zipfile
from pathlib import Path
repo, zip_path, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
with zipfile.ZipFile(zip_path) as z:
    z.extractall(out)
root = out / 'SDNUI'
h = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
tracked = subprocess.check_output(['git', '-C', str(repo), 'ls-files', 'python/sdfx', 'models', 'licenses'], text=True).splitlines()
matched, missing, differ = 0, [], []
for rel in tracked:
    for cand in (root / rel, root / 'app' / rel):
        if cand.is_file():
            (matched := matched + 1) if h(cand) == h(repo / rel) else differ.append(rel); break
    else:
        missing.append(rel)
jar = root / 'app' / 'stablediffusion-nui.jar'
print({'matched': matched, 'missing': len(missing), 'differ': differ,
       'jar_equal': h(jar) == h(repo / 'target/stablediffusion-nui.jar'), 'jar': h(jar)[:16],
       'exe': (root / 'SDNUI.exe').is_file(), 'notices': (root / 'THIRD_PARTY_NOTICES.md').is_file() and h(root / 'THIRD_PARTY_NOTICES.md') == h(repo / 'THIRD_PARTY_NOTICES.md')})
print('missing sample:', missing[:8])
