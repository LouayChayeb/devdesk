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
import os
import shutil
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE = "io.github.louch.devdesk"
# The same helpers mutation-score.py leaves out: fakes and test engines say nothing about the product.
HELPER = re.compile(r"(Test($|\$)|\.Fake|\.Scripted|\.TestEngine|\.TestContainer|\.TestPostgres|\.TestMysql|\.TestJvm)")
CAUGHT = ("KILLED", "TIMED_OUT", "MEMORY_ERROR")
# These modules' tests start real databases in Docker, which share WSL's memory with the test JVMs: one JVM at a time.
ONE_JVM = {"engine-docker", "engine-data"}
# Below this, the part is stopped before Linux runs out of memory (the test JVMs and databases share it).
MIN_AVAILABLE_MB = 1200
# WSL keeps Linux's disk in a file on Windows' C:, which grows as Linux writes. When C: is full, every write in Linux
# fails at once and WSL dies (seen 3 times), even though Linux itself still reports hundreds of GB free.
MIN_DISK_FREE_MB = 5000
DISKS = [d for d in ("/", "/mnt/c") if os.path.isdir(d)]

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
        threads = ["-PpitestThreads=1"] if module in ONE_JVM else []
        # --no-daemon: PIT and its test JVMs are then this script's descendants, so the memory guard can stop them.
        code = guarded([str(ROOT / "gradlew"), "-q", "--no-daemon", f":{module}:pitest",
                        f"-PpitestTargetClasses={','.join(targets)}",
                        f"-PpitestReportDir={report}", *threads], out / "memory.log")
        if code in ("low memory", "low disk"):
            print(f"{name}: stopped, {code} (see memory.log); run again to retry this part", file=sys.stderr)
            sys.exit(1)
        xml = report / "mutations.xml"
        if code != 0 or not xml.exists():
            print(f"{name}: PIT failed (exit {code}); run again to retry this part", file=sys.stderr)
            sys.exit(1)
        counts = score(xml)
        total = sum(counts.values())
        caught = sum(counts.get(s, 0) for s in CAUGHT)
        result.write_text(json.dumps({"module": module, "targets": targets, "counts": counts}, indent=1))
        print(f"{name}: {caught}/{total}", flush=True)


def available_mb():
    for line in open("/proc/meminfo"):
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) // 1024
    return 0


def disk_free_mb():
    """The fullest of Linux's own disk and, under WSL, Windows' C: that holds it."""
    return min(shutil.disk_usage(d).free // (1024 * 1024) for d in DISKS)


def descendants(root):
    """Every process below root, found through /proc/<pid>/stat's parent field (setsid doesn't change the parent)."""
    children = {}
    for entry in os.listdir("/proc"):
        if entry.isdigit():
            try:
                stat = open(f"/proc/{entry}/stat").read()
            except OSError:
                continue
            ppid = int(stat[stat.rindex(")") + 2:].split()[1])
            children.setdefault(ppid, []).append(int(entry))
    found, todo = [], [root]
    while todo:
        for child in children.get(todo.pop(), []):
            found.append(child)
            todo.append(child)
    return found


def guarded(command, log):
    """Runs command, logging memory and disk every 30 s (fsynced, so it survives a VM crash); stops it if either runs
    low."""
    child = subprocess.Popen(command, cwd=ROOT)
    last_log = 0.0
    with open(log, "a") as f:
        while child.poll() is None:
            mb, disk = available_mb(), disk_free_mb()
            low = mb < MIN_AVAILABLE_MB or disk < MIN_DISK_FREE_MB
            if time.monotonic() - last_log > 30 or low:
                f.write(f"{time.strftime('%H:%M:%S')} available={mb}MB disk_free={disk}MB "
                        f"processes={len(descendants(child.pid))}\n")
                f.flush()
                os.fsync(f.fileno())
                last_log = time.monotonic()
            if low:
                # Only our own descendants: PIT, its test JVMs, Gradle. Their test containers are reaped next run.
                for pid in [child.pid, *descendants(child.pid)]:
                    try:
                        os.kill(pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                child.wait()
                return "low memory" if mb < MIN_AVAILABLE_MB else "low disk"
            time.sleep(5)
    return child.returncode


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
