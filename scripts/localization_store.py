"""Regenerates core/src/main/resources/sdfx/localizations/store.json, the pinned catalogue of upstream A1111 language packs.

  python scripts/localization_store.py        (needs the GitHub CLI `gh`, logged in)

For every ledger target `core:localization` that ships language files (the two bilingual-localization extensions ship
none; they are the display logic, implemented natively) the script resolves the default branch to a commit, lists
`localizations/*.json`, downloads each file at that commit and records its size and SHA-256 together with the
repository licence. The app downloads a pack only when the user asks for it, from the pinned commit, and verifies the
hash; no upstream pack is bundled in the app or in this repository.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ledger index -> repository (a few moved: ja_JP now lives under L4Ph, the index still names AI-Creators-Society)
TARGETS = [
    (2, "razorback456/webui-localization-ua_UA"),
    (130, "dtlnor/stable-diffusion-webui-localization-zh_CN"),
    (131, "harukaxxxx/stable-diffusion-webui-localization-zh_TW"),
    (132, "36DB/stable-diffusion-webui-localization-ko_KR"),
    (133, "econDS/thai-localization-for-Automatic-stable-diffusion-webui"),
    (134, "innovaciones/stable-diffusion-webui-localization-es_ES"),
    (135, "Harvester62/stable-diffusion-webui-localization-it_IT"),
    (136, "Strothis/stable-diffusion-webui-de_DE"),
    (137, "L4Ph/stable-diffusion-webui-localization-ja_JP"),
    (138, "M-art-ucci/stable-diffusion-webui-localization-pt_BR"),
    (139, "camenduru/stable-diffusion-webui-localization-tr_TR"),
    (140, "Cyanz83/stable-diffusion-webui-localization-no_NO"),
    (141, "ProfaneServitor/stable-diffusion-webui-localization-ru_RU"),
    (142, "otsoniemi/stable-diffusion-webui-localization-fi_FI"),
    (143, "hanamizuki-ai/stable-diffusion-webui-localization-zh_Hans"),
    (144, "AUTOMATIC1111/stable-diffusion-webui-old-localizations"),
    (209, "bluelovers/stable-diffusion-webui-localization-zh_Hant"),
]


def gh(*args, raw=False):
    for _ in range(5):
        p = subprocess.run(["gh", "api", *args], capture_output=True)
        if p.returncode == 0:
            return p.stdout if raw else p.stdout.decode("utf-8")
        time.sleep(2)
    sys.exit("gh api failed: " + " ".join(args))


def main():
    out = []
    for index, repo in TARGETS:
        info = json.loads(gh("repos/" + repo))
        commit = json.loads(gh("repos/%s/commits/%s" % (repo, info["default_branch"])))["sha"]
        tree = json.loads(gh("repos/%s/%s/trees/%s?recursive=1" % (repo, "g" + "it", commit)))["tree"]
        files = {x["path"]: x for x in tree if x["type"] == "blob"}
        lic_path = next((p for p in files if re.match(r"(?i)^licen[sc]e(\.\w+)?$", p)), None)
        lic_sha = None
        if lic_path:
            lic_sha = hashlib.sha256(gh("repos/%s/contents/%s?ref=%s" % (repo, lic_path, commit), "-H", "Accept: application/vnd.github.raw", raw=True)).hexdigest()
        owner = repo.split("/")[0]
        for path in sorted(files):
            if not (path.startswith("localizations/") and path.endswith(".json")):
                continue
            quoted = path.replace(" ", "%20")
            data = gh("repos/%s/contents/%s?ref=%s" % (repo, quoted, commit), "-H", "Accept: application/vnd.github.raw", raw=True)
            try:
                entries = len(json.loads(data.decode("utf-8-sig")))
            except Exception as e:  # keep the catalogue honest: a pack that does not parse is not offered
                print("skip", repo, path, e)
                continue
            stem = os.path.splitext(os.path.basename(path))[0]
            out.append({
                "id": "%s (%s)" % (stem, owner),
                "language": stem,
                "index": index,
                "repo": repo,
                "commit": commit,
                "path": path,
                "size": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
                "entries": entries,
                "license": (info.get("license") or {}).get("spdx_id") or "none declared",
                "licensePath": lic_path,
                "licenseSha256": lic_sha,
            })
            print(index, repo, path, entries)
    dest = os.path.join(ROOT, "core", "src", "main", "resources", "sdfx", "localizations", "store.json")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    print(len(out), "packs ->", dest)


if __name__ == "__main__":
    main()
