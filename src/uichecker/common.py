import os
import tempfile
import json
import glob
import csv
from lark import Lark
import pexpect

from msbase.utils import load_json, write_pretty_json, log_progress, readlines, getenv
from msbase.subprocess_ import try_call_std
from xml.sax.saxutils import escape

from typing import Dict, Any, List, Set

syntax = """
?start: decl
?decl: ".decl" NAME "(" arguments ")"
arguments: argvalue ("," argvalue)*
?argvalue: NAME ":" NAME

COMMENT: /\/\/.*/

%import common.CNAME -> NAME
%ignore " "
%ignore COMMENT
"""

class SpecParser(object):
    def __init__(self, spec_path: str):
        decl_parser = Lark(syntax) # type: ignore

        self.decls = {}
        self.number_types = set(["number"])
        self.symbol_types = set(["symbol"])

        with open(spec_path, "r") as spec_f:
            lines = []
            for line in spec_f.read().split("\n"):
                if line.startswith("#include"):
                    included = line.split()[1].strip("\"")
                    lines.extend(open(os.path.dirname(spec_path) + "/" + included, "r").read().split("\n"))
                else:
                    lines.append(line)
            for line in lines:
                if line.startswith(".decl"):
                    tree = decl_parser.parse(line)
                    assert tree.data == "decl"
                    assert tree.children[0].type == "NAME" # type: ignore
                    decl_name = tree.children[0].value # type: ignore
                    assert tree.children[1].data == "arguments" # type: ignore
                    args = []
                    for arg in tree.children[1].children: # type: ignore
                        assert arg.data == "argvalue" # type: ignore
                        assert arg.children[0].type == "NAME" # type: ignore
                        assert arg.children[1].type == "NAME" # type: ignore
                        args.append((arg.children[0].value, arg.children[1].value)) # type: ignore
                    self.decls[decl_name] = args
                elif line.startswith(".number_type"):
                    self.number_types.add(line.split()[1])
                elif line.startswith(".symbol_type"):
                    self.symbol_types.add(line.split()[1])


def parse_seconds(line: str):
    time_segments = line.split(":")
    seconds = 0.0
    assert len(time_segments) <= 3 and len(time_segments) >= 1
    seconds += float(time_segments[-1]) # type: ignore
    if len(time_segments) >= 2:
        seconds += 60 * float(time_segments[-2]) # type: ignore
    if len(time_segments) >= 3:
        seconds += 60 * 60 * float(time_segments[-3]) # type: ignore
    return seconds

MARKII_TIMEOUT_SECONDS = int(getenv("MARKII_TIMEOUT_SECONDS", "1200"))

def run_markii(apk: str, facts_dir: str):
    os.system("mkdir -p " + facts_dir)
    # Run markii
    try_call_std(["bash", "markii/build-run-markii.sh", apk, facts_dir],
                 output=False, timeout_s=MARKII_TIMEOUT_SECONDS)

SOUFFLE_TIMEOUT_SECONDS = int(getenv("SOUFFLE_TIMEOUT_SECONDS", "360"))

class SouffleExplain(object):
    def __init__(self, facts_dir: str, spec_path: str):
        os.system("mkdir -p tmp/output") # we don't need CSV output here
        self.counter = 0
        self.souffle_cmd = f"souffle -t explain -F{facts_dir} {spec_path} -Dtmp/output"
        self.launch()
        # import sys
        # p.logfile = sys.stdout.buffer # debug

    def launch(self):
        print("Launching explainer")
        try:
            self.p = pexpect.spawn(self.souffle_cmd)
            self.p.expect('> ', timeout=SOUFFLE_TIMEOUT_SECONDS)
            self.p.sendline("format json")
            self.p.sendline()
        except pexpect.exceptions.TIMEOUT as e:
            print("Timeout launching: ")
            print(self.souffle_cmd)
            raise e

    def explain(self, query: str):
        explain_json = tempfile.NamedTemporaryFile(delete=False).name
        explain_json_force = tempfile.NamedTemporaryFile(delete=False).name
        lines = [
            "output " + explain_json,
            "explain " + query,
            "output " + explain_json_force,
            "explain dummy(1)"
        ]
        for line in lines:
            self.p.expect('> ', )
            self.p.sendline(line)
            self.p.sendline()
        try:
            inference = open(explain_json, "r").read().replace("\;", ";") # fix invalid \escape
            ret = json.loads(inference)
            os.unlink(explain_json)
        except Exception as e:
            print(self.souffle_cmd)
            for line in lines:
                print("> " + line)
            print("Failed to decode " + explain_json + "\n%s" % e)
            self.launch()
            return None
        return ret

    def close(self):
        self.p.expect('> ')
        assert self.p.isalive()
        self.p.sendline("exit")
        self.p.sendline()
        self.p.close()

def produce_report(report_prefixes: str, facts_dir: str, spec_path: str, output_dir: str, apk_name: str):
    report_path = output_dir + "/report.json"
    if os.path.exists(report_path):
        print("Report exists " + report_path)
        return

    spec_parser = SpecParser(spec_path)

    print("Producing report...")
    report = {} # type: Dict[str, Any]

    id_names = {}
    with open("%s/idName.facts" % facts_dir, "r") as f: # type: ignore
        fieldnames = [ name for name, _ in spec_parser.decls["idName"] ]
        reader = csv.DictReader(f, fieldnames=fieldnames, delimiter='\t', quoting=csv.QUOTE_NONE)
        for row in reader:
            id_names[row["v"]] = row["name"]

    id_classes = {}
    with open("%s/viewClass.facts" % facts_dir, "r") as f: # type: ignore
        fieldnames = [ name for name, _ in spec_parser.decls["viewClass"] ]
        reader = csv.DictReader(f, fieldnames=fieldnames, delimiter='\t', quoting=csv.QUOTE_NONE) # type: ignore
        for row in reader:
            id_classes[row["v"]] = row["class"]

    text_content = {} # type: Dict[str, Set[str]]
    # with open("%s/textContent.facts" % facts_dir, "r") as f: # type: ignore
    #     fieldnames = [ name for name, _ in spec_parser.decls["textContent"] ]
    #     reader = csv.DictReader(f, fieldnames=fieldnames, delimiter='\t', quoting=csv.QUOTE_NONE) # type: ignore
    #     for row in reader:
    #         if row["v"] not in text_content:
    #             text_content[row["v"]] = set()
    #         text_content[row["v"]].add(row["t"])

    parent_views = {} # type: Dict[str, Set[str]]
    with open("%s/containsView.facts" % facts_dir, "r") as f: # type: ignore
        fieldnames = [ name for name, _ in spec_parser.decls["containsView"] ]
        reader = csv.DictReader(f, fieldnames=fieldnames, delimiter='\t', quoting=csv.QUOTE_NONE)
        for row in reader:
            if row["u"] not in parent_views:
                parent_views[row["u"]] = set()
            parent_views[row["u"]].add(row["v"])

    root_view = {} # type: Dict[str, str]
    with open("%s/rootView.facts" % facts_dir, "r") as f: # type: ignore
        fieldnames = [ name for name, _ in spec_parser.decls["rootView"] ]
        reader = csv.DictReader(f, fieldnames=fieldnames, delimiter='\t', quoting=csv.QUOTE_NONE)
        for row in reader:
            root_view[row["v"]] = row["act"]

    explainer = SouffleExplain(facts_dir, spec_path)
    violated = set()
    report_prefixes = report_prefixes.split(',') # type: ignore
    for f in log_progress(glob.glob(output_dir + "/*.csv"), desc="CSV", print_item=True): # type: ignore
        decl_name = os.path.basename(f).replace(".csv", "") # type: ignore
        if all(not decl_name.startswith(report_prefix) for report_prefix in report_prefixes):
            continue
        if decl_name not in spec_parser.decls: continue
        decl_args = spec_parser.decls[decl_name] # type: ignore
        with open(f, "r") as fh: # type: ignore
            content = fh.read().strip()
            if content:
                report[decl_name] = []
                lines = content.split('\n')[:10]
                for row in log_progress(lines): # type: ignore
                    violated.add(decl_name)
                    model_value_strs = []
                    model_details = []
                    values = row.split('\t') # type: ignore
                    assert len(values) == len(decl_args)
                    for decl, val in zip(decl_args, values):
                        name = decl[0]
                        type_ = decl[1]
                        props = {}
                        if type_ == "ViewID":
                            if val in id_names:
                                props["idName"] = id_names[val]
                            else:
                                parents = []
                                if val in parent_views:
                                    parents.extend(list(parent_views[val]))
                                    changed = True
                                    while changed:
                                        changed = False
                                        for parent in parents:
                                            if parent not in parent_views:
                                                continue
                                            for grandparent in parent_views[parent]:
                                                if grandparent not in parents:
                                                    parents.append(grandparent)
                                                    changed = True
                                parent_ids = [ id_names[parent] for parent in parents if parent in id_names ] # type: ignore
                                props["parent_ids"] = parent_ids # type: ignore
                            if val in id_classes:
                                props["class"] = id_classes[val]
                            if val in text_content:
                                props["text"] = text_content[val] # type: ignore
                        model_details.append({
                            "name": name,
                            'type': type_,
                            "value": val,
                            "props": props
                        })
                        if type_ in spec_parser.number_types:
                            model_value_strs.append(val)
                        elif type_ in spec_parser.symbol_types:
                            model_value_strs.append('"%s"' % val)
                        else:
                            raise Exception("Unknown type: %s" + type_)

                    query = decl_name + "(" + ", ".join(model_value_strs) + ")"
                    inference = explainer.explain(query)
                    if inference is not None:
                        del inference["rules"]
                    report[decl_name].append({
                        "details": model_details,
                        "inference": inference
                    })

    explainer.close()
    write_pretty_json(report, report_path)
    print("Report written to " + report_path)

def load_results(d: str):
    results = {}
    for f in glob.glob(d + "/*.csv"):
        rule_name = os.path.basename(f).replace(".csv", "")
        with open(f, "r") as fh:
            content = fh.read().strip()
            if "\n" in content:
                results[rule_name] = content.split("\n")
            else:
                if content:
                    results[rule_name] = [content]
                else:
                    results[rule_name] = []
    return results

def get_successful_results(o: str):
    if not os.path.exists(o):
        return None
    if o is None:
        return None
    results = load_results(o)
    if len(results) == 0:
        return None
    return results
