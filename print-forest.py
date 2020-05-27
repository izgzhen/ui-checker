from common import SpecParser
import csv
import sys

from termcolor import colored

spec_path = sys.argv[1]
facts_dir = sys.argv[2]
suffix = ""
if len(sys.argv) > 3:
    suffix = sys.argv[3]

spec_parser = SpecParser(spec_path)

report = {} # type: Dict[str, Any]

id_names = {}
with open("%s/idName%s.facts" % (facts_dir, suffix), "r") as f: # type: ignore
    fieldnames = [ name for name, _ in spec_parser.decls["idName"] ]
    reader = csv.DictReader(f, fieldnames=fieldnames, delimiter='\t', quoting=csv.QUOTE_NONE)
    for row in reader:
        id_names[row["v"]] = row["name"]

id_classes = {}
with open("%s/viewClass%s.facts" % (facts_dir, suffix), "r") as f: # type: ignore
    fieldnames = [ name for name, _ in spec_parser.decls["viewClass"] ]
    reader = csv.DictReader(f, fieldnames=fieldnames, delimiter='\t', quoting=csv.QUOTE_NONE) # type: ignore
    for row in reader:
        id_classes[row["v"]] = row["class"]

children_views = {} # type: Dict[str, Set[str]]
parent_views = {} # type: Dict[str, Set[str]]

with open("%s/containsView%s.facts" % (facts_dir, suffix), "r") as f: # type: ignore
    fieldnames = [ name for name, _ in spec_parser.decls["containsView"] ]
    reader = csv.DictReader(f, fieldnames=fieldnames, delimiter='\t', quoting=csv.QUOTE_NONE)
    for row in reader:
        if row["v"] not in children_views:
            children_views[row["v"]] = set()
        if row["u"] not in parent_views:
            parent_views[row["u"]] = set()
        children_views[row["v"]].add(row["u"])
        parent_views[row["u"]].add(row["v"])

printed = set()

def print_node(child, depth):
    extra = ""
    if child in printed:
        extra += "..."
    if child in id_names:
        extra += " id=%s," % colored(id_names[child], "green")
    if child in id_classes:
        extra += " class=%s," % colored(id_classes[child], "green")
    print("\t|" * depth + child + extra)

def print_forest(parent: str, depth=1):
    if parent in printed:
        print_node(parent, depth)
        return
    printed.add(parent)
    for child in children_views[parent]:
        print_node(child, depth)
        if child in children_views:
            print_forest(child, depth=depth+1)

for parent in children_views:
    if parent not in parent_views:
        print_node(parent, 0)
        print_forest(parent)
        print()