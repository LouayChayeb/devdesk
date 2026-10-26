# Mutation testing in resumable parts. A full PIT run takes over an hour; when the laptop sleeps or goes offline the
# whole run is lost. This splits each module into parts of a few minutes, runs them one by one, and saves each part's
# result as soon as it is done. Run it again after an interruption: finished parts are skipped, only the part that was
# cut off runs again. (PIT's history files can't help: open source PIT no longer supports them.)
#
# Usage:  python3 scripts/mutation-chunks.py [--out DIR] [--only MODULE] [--list]
#         python3 scripts/mutation-chunks.py --table      (merge the saved parts into one score per module)
# Results: DIR/<part>.json (score) and DIR/<part>/mutations.xml (the report); DIR defaults to ~/devdesk-trials/_mutation
import argparse
import json
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE = "io.github.louch.devdesk"
# The same helpers mutation-score.py leaves out: fakes and test engines say nothing about the product.
HELPER = re.compile(r"(Test($|\$)|\.Fake|\.Scripted|\.TestEngine|\.TestContainer|\.TestPostgres|\.TestMysql)")
CAUGHT = ("KILLED", "TIMED_OUT", "MEMORY_ERROR")

# engine-core by package, grouped so each part is a similar size; the rest of a module in one part.
CORE_GROUPS = [["data"], ["setup"], ["http"], ["diagnosis", "config"], ["supervisor", "changes"],
               ["doctor", "history", "project", "state", "process", "testing"]]


def classes_of(module, package):
    """Top-level class names in a package of a module, sorted."""
    folder = ROOT / module / "src/main/java" / BASE.replace(".", "/") / package
    return sorted(p.stem for p in folder.glob("*.java") if p.stem != "package-info")


def split(names, parts):
    """names into `parts` lists of about the same length."""
    size = -(-len(names) // parts)
    return [names[i:i + size] for i in range(0, len(names), size)]


def rest(module, top, taken):
    """Globs for every package of a module not in `taken`, plus the classes directly in the top package."""
    folder = ROOT / module / "src/main/java" / BASE.replace(".", "/") / top
    packages = sorted(p.name for p in folder.iterdir() if p.is_dir() and p.name not in taken)
    return [f"{BASE}.{top}.{p}.*" for p in packages] + [f"{BASE}.{top}.{n}*" for n in classes_of(module, top)]


def chunks():
    """(part name, module, PIT target globs) for every part, in run order."""
    out = []
    core = f"{BASE}.core"
    grouped = {p for g in CORE_GROUPS for p in g}
    for group in CORE_GROUPS:
        out.append(("engine-core-" + "-".join(group), "engine-core", [f"{core}.{p}.*" for p in group]))
    out.append(("engine-core-rest", "engine-core", rest("engine-core", "core", grouped)))
    out.append(("engine-linux-process", "engine-linux", [f"{BASE}.linux.process.*"]))
    out.append(("engine-linux-rest", "engine-linux", rest("engine-linux", "linux", {"process"})))
    out.append(("engine-docker", "engine-docker", [f"{BASE}.docker.*"]))
    for i, names in enumerate(split(classes_of("engine-data", "data"), 3), 1):
        out.append((f"engine-data-{i}", "engine-data", [f"{BASE}.data.{n}*" for n in names]))
    for i, names in enumerate(split(classes_of("daemon", "daemon"), 3), 1):
        out.append((f"daemon-{i}", "daemon", [f"{BASE}.daemon.{n}*" for n in names]))
    return out


def score(xml):
    """Counts of product mutants by status."""
    counts = {}
    for m in ET.parse(xml).getroot().iter("mutation"):
        if not HELPER.search(m.find("mutatedClass").text):
            counts[m.get("status")] = counts.get(m.get("status"), 0) + 1
    return counts


def run(out, only):
    out.mkdir(parents=True, exist_ok=True)
    for name, module, targets in chunks():
        if only and module != only:
            continue
        result = out / f"{name}.json"
        if result.exists():
            print(f"{name}: done already, skipped")
            continue
        report = out / name
        print(f"{name}: running", flush=True)
        code = subprocess.call([str(ROOT / "gradlew"), "-q", f":{module}:pitest",
                                f"-PpitestTargetClasses={','.join(targets)}",
                                f"-PpitestReportDir={report}"], cwd=ROOT)
        xml = report / "mutations.xml"
        if code != 0 or not xml.exists():
            print(f"{name}: PIT failed (exit {code}); run again to retry this part", file=sys.stderr)
            sys.exit(1)
        counts = score(xml)
        total = sum(counts.values())
        caught = sum(counts.get(s, 0) for s in CAUGHT)
        result.write_text(json.dumps({"module": module, "targets": targets, "counts": counts}, indent=1))
        print(f"{name}: {caught}/{total}", flush=True)


def table(out):
    """Per module: caught/total over all its parts (a mutant is counted once even if two parts both saw it)."""
    by_module = {}
    for name, module, _ in chunks():
        xml = out / name / "mutations.xml"
        if not (out / f"{name}.json").exists():
            by_module.setdefault(module, {})["missing"] = True
            continue
        status = by_module.setdefault(module, {}).setdefault("status", {})
        for m in ET.parse(xml).getroot().iter("mutation"):
            cls = m.find("mutatedClass").text
            if HELPER.search(cls):
                continue
            key = (cls, m.find("mutatedMethod").text, m.find("lineNumber").text, m.find("mutator").text,
                   m.find("description").text)
            status[key] = m.get("status")
    for module, info in by_module.items():
        status = info.get("status", {})
        caught = sum(1 for s in status.values() if s in CAUGHT)
        missing = " (some parts not run yet)" if info.get("missing") else ""
        pct = f"{100 * caught / len(status):.1f}%" if status else "-"
        print(f"{module}: {caught}/{len(status)} = {pct}{missing}")


if __name__ == "__main__":
    a = argparse.ArgumentParser()
    a.add_argument("--out", default=str(pathlib.Path.home() / "devdesk-trials/_mutation"))
    a.add_argument("--only")
    a.add_argument("--list", action="store_true")
    a.add_argument("--table", action="store_true")
    args = a.parse_args()
    out = pathlib.Path(args.out)
    if args.list:
        for name, module, targets in chunks():
            print(name, module, ",".join(targets))
    elif args.table:
        table(out)
    else:
        run(out, args.only)
