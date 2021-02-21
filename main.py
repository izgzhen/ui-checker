#!/usr/bin/env python3

import tempfile
import os
import glob
import sys
from termcolor import cprint
import time
import shutil

from msbase.subprocess_ import try_call_std
from msbase.utils import load_json, write_pretty_json, file_size_mb

from uichecker.common import run_markii, produce_report, parse_seconds, run_gator

import logging

logger = logging.getLogger("ui-checker")

if os.getenv("GTIME"):
    gtime = os.getenv("GTIME")
else:
    gtime = "gtime"

SCRIPT_DIR = os.path.dirname(os.path.realpath(__file__))
uicheck_start_time = time.time()

apk = sys.argv[1]
assert os.path.exists(apk) and apk.endswith(".apk"), f"Invalid APK path: {apk}"
apk = os.path.realpath(apk)
ic3_model = apk.replace(".apk", "_ic3.txt")
if not os.path.exists(ic3_model):
    logger.info("IC3 model is not available at %s, use default analysis" % ic3_model)

spec_path = sys.argv[2]
assert os.path.exists(spec_path) and spec_path.endswith(".dl"), f"Invalid spec path: {spec_path}"
spec_path = os.path.realpath(spec_path)

if len(sys.argv) > 3:
    apk_name = sys.argv[3]
else:
    apk_name = os.path.basename(apk)

os.chdir(SCRIPT_DIR)

if os.getenv("ENGINE"):
    ENGINE = os.getenv("ENGINE")
else:
    ENGINE = "markii"
assert ENGINE in ["markii", "gator", "markii-ci-fs", "markii-ci-fi", "markii-elf-ns", "markii-elf-nb"]

facts_dir = SCRIPT_DIR + "/tmp_%s/%s.facts_dir" % (ENGINE, apk_name)
output_dir = SCRIPT_DIR + "/output_%s/%s/" % (ENGINE, apk_name)
os.system("mkdir -p %s" % output_dir)

SOLVE_DL_ONLY = os.getenv("SOLVE_DL_ONLY")

os.system("mkdir -p tmp")

print(f"Android UI Checker\nspec:{spec_path}\napk:{apk}\nengine:{ENGINE}\n")

markii_duration_seconds = None
gator_duration_seconds = None

souffle_stats = {}

def valid_fact_dir(d: str):
    if not os.path.isdir(d): return False
    facts = glob.glob(d + "/*.facts")
    if not facts: return False
    if not any(file_size_mb(fact) > 0 for fact in facts): return False
    return True

if (not valid_fact_dir(facts_dir) or os.getenv('FORCE_RERUN')) and SOLVE_DL_ONLY != "1":
    start_time = time.time()
    if ENGINE == "markii":
        run_markii(apk, facts_dir)
        markii_duration_seconds = time.time() - start_time
    elif ENGINE == "markii-elf-nb":
        pass
    elif ENGINE == "markii-elf-ns":
        pass
    elif ENGINE == "markii-ci-fs":
        run_markii(apk, facts_dir, vasco_mode="context-insensitive,flow-sensitive")
        markii_duration_seconds = time.time() - start_time
    elif ENGINE == "markii-ci-fi":
        run_markii(apk, facts_dir, vasco_mode="context-insensitive,flow-insensitive")
        markii_duration_seconds = time.time() - start_time
    elif ENGINE == "gator":
        run_gator(apk, facts_dir)
        gator_duration_seconds = time.time() - start_time

    with open(facts_dir + "/dummy.facts", "w") as f:
        f.write("1")
else:
    cprint("Exists " + os.path.realpath(facts_dir), "green")

souffle_duration_seconds = None

if os.path.isdir(facts_dir):
    if os.getenv("REPORT"):
        produce_report(os.getenv("REPORT"), facts_dir, spec_path, output_dir, apk_name) # type: ignore
    else:
        souffle_start_time = time.time()
        # Run Souffle
        stdout, stderr, code = try_call_std([gtime, '-f', 'gtime_memory: %M\ngtime_seconds: %E\ngtime_user_seconds: %U',
                                            "souffle", "-w", "-F", facts_dir, spec_path, "-D", output_dir], output=False)
        assert "Error" not in stdout, stdout
        assert "Error" not in stderr, stderr
        souffle_duration_seconds = time.time() - souffle_start_time
        for line in stderr.split("\n"):
            if "gtime_memory: " in line:
                souffle_stats["souffle_gtime_memory_KB"] = float(line.split("gtime_memory: ")[1].strip())
            if "gtime_seconds: " in line:
                seconds = line.split("gtime_seconds: ")[1].strip()
                souffle_stats["souffle_gtime_duration_seconds"] = parse_seconds(seconds)
            if "gtime_user_seconds: " in line:
                seconds = line.split("gtime_user_seconds: ")[1].strip()
                souffle_stats["souffle_gtime_user_duration_seconds"] = parse_seconds(seconds)

    print("Souffle results written to " + os.path.realpath(output_dir))

    violated = set()
    for f in glob.glob(output_dir + "/*.csv"): # type: ignore
        if file_size_mb(f) > 1.0:
            # minimize the output if too big
            tmp = tempfile.NamedTemporaryFile(delete=False).name
            assert os.system("shuf -n 10 %s > %s" % (f, tmp)) == 0
            shutil.move(tmp, f) # type: ignore
        decl_name = os.path.basename(f).replace(".csv", "") # type: ignore
        if len(open(f, "r").read().strip()) > 0: # type: ignore
            violated.add(decl_name)
    if len(violated):
        cprint("======== Violated ========", "red")
        for rule in sorted(list(violated)):
            cprint('- ' + rule, "red")

total_duration_seconds = time.time() - uicheck_start_time

print("Spent %.2f seconds" % total_duration_seconds)

stat_results = {
    "total_duration_seconds": total_duration_seconds,
    "souffle_duration_seconds": souffle_duration_seconds,
    "markii_duration_seconds": markii_duration_seconds,
    "gator_duration_seconds": gator_duration_seconds,
}

if souffle_stats:
    for k, v in souffle_stats.items():
        stat_results[k] = v

if os.path.exists(output_dir + "/uicheck-results.json"):
    old_stat_results = load_json(output_dir + "/uicheck-results.json")
    for k, v in stat_results.items(): # type: ignore
        if v is None and k in old_stat_results:
            stat_results[k] = old_stat_results[k]
write_pretty_json(stat_results,  output_dir + "/uicheck-results.json")
