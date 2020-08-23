# Translate Vesper program to Datalog

import sys
import os

from lark import Lark

syntax = """
?start: prog
?prog: import* decl* stmt* assert*
import: "from" NAME "import" NAME ";"
decl: NAME NAME ";"
stmt: "assume" expr ";"         -> assume
expr: expr binop expr           -> biexpr
    | uop expr                  -> uexpr
    | NAME "(" arguments ")"    -> callexpr
    | term                      -> termexpr
binop : "=="                    -> eqop
      | "<"                     -> leop
uop : "!"                       -> negop
arguments: term ("," term)*
term: NAME                      -> var
    | NUMBER                    -> number
assert: "assert" expr ";"

COMMENT: /\/\/.*/

%import common.CNAME -> NAME
%import common.WS
%import common.NUMBER

%ignore WS
%ignore COMMENT
"""
vs_parser = Lark(syntax) # type: ignore

class Expr(object):
    def __init__(self, tree = None):
        self.type = None
        if tree is None:
            return
        if tree.data == "biexpr":
            self.type = "biexpr"
            e1 = tree.children[0]
            binop = tree.children[1]
            e2 = tree.children[2]
            self.binop = binop.data
            self.e1 = Expr(e1)
            self.e2 = Expr(e2)
        elif tree.data == "uexpr":
            self.type = "uexpr"
            uop = tree.children[0]
            e = tree.children[1]
            self.uop = uop.data
            self.e = Expr(e)
        elif tree.data == "callexpr":
            self.type = "callexpr"
            self.f = tree.children[0].value
            self.args = [ Expr(arg) for arg in tree.children[1].children ]
        elif tree.data == "var":
            self.__init_term(tree)
        elif tree.data == "termexpr":
            self.__init_term(tree.children[0])
        else:
            raise Exception(tree)

    @staticmethod
    def var_expr(x: str):
        e = Expr()
        e.type = "var"
        e.var = x
        return e

    @staticmethod
    def call_expr(f: str, *args):
        e = Expr()
        e.type = "callexpr"
        e.f = f
        assert all(type(arg) is Expr for arg in args), args
        e.args = args
        return e

    @staticmethod
    def neg_expr(e: 'Expr'):
        parent = Expr()
        parent.type = "uexpr"
        parent.uop = "negop"
        parent.e = e
        return parent

    def __init_term(self, tree):
        if tree.data == "var":
            self.type = "var"
            self.var = tree.children[0].value
        elif tree.data == "number":
            self.type = "number"
            self.number = int(tree.children[0].value)
        else:
            raise Exception(tree)

    def __repr__(self):
        return self.__str__()

    def __str__(self):
        if self.type == "var":
            return self.var
        elif self.type == "biexpr":
            return f"{self.e1} {self.binop} {self.e2}"
        elif self.type == "callexpr":
            return f"{self.f} {self.args}"
        else:
            return super().__str__()

    def translate(self):
        if self.type == "var":
            return self.var
        elif self.type == "number":
            return str(self.number)
        elif self.type == "uexpr":
            if self.uop == "negop":
                return f"!({self.e.translate()})"
            else:
                raise Exception(self)
        elif self.type == "biexpr":
            if self.binop == "eqop":
                return f"{self.e1.translate()} = {self.e2.translate()}"
            elif self.binop == "leop":
                return f"{self.e1.translate()} < {self.e2.translate()}"
            else:
                # FIXME: process "and"
                raise Exception(self)
        elif self.type == "callexpr":
            args = ", ".join([arg.translate() for arg in self.args])
            return f"{self.f}({args})"
        else:
            raise Exception(self)

class Prog(object):
    def __init__(self, name, tree):
        self.name = name
        self.imports = []
        self.decls = []
        self.assumes = []
        self.asserts = []

        assert tree.data == "prog"
        for child in tree.children:
            if child.data == "import":
                c1, c2 = child.children
                assert c1.type == "NAME" and c2.type == 'NAME'
                self.imports.append((c1.value, c2.value))
                continue
            elif child.data == "decl":
                c1, c2 = child.children
                assert c1.type == "NAME" and c2.type == 'NAME'
                ty_name = c1.value
                var_name = c2.value
                if any(imported_name == ty_name for _, imported_name in self.imports):
                    # FIXME: analyze imports
                    if ty_name == "adView":
                        ty_name = "ViewID"
                        self.assumes.append(Expr.call_expr("adView", Expr.var_expr(var_name)))
                    else:
                        raise Exception(ty_name)
                self.decls.append((ty_name, var_name))
                continue
            elif child.data == "assume":
                e = child.children[0]
                self.assumes.append(Expr(e))
            elif child.data == "assert":
                e = child.children[0]
                self.asserts.append(Expr(e))
            else:
                raise Exception(child)

    def __str__(self):
        return f"imports: {self.imports}\ndecls: {self.decls}" + \
            f"\nassumes: {self.assumes}\nasserts: {self.asserts}"

    def translate(self) -> str:
        ret = ""
        ret += "// GENERATED" + "\n"
        typed_args = ", ".join([f"{var_name}: {ty_name}" for ty_name, var_name in self.decls])
        ret += f".decl {self.name}({typed_args})" + "\n"
        conjuncts = []
        # assume_1 /\ ... /\ assume_n /\ ! (assert_1 /\ assert2 /\ ...)
        # assume_1 /\ ... /\ assume_n /\ (!assert_1 ; !assert2 ; ...)
        for assume in self.assumes:
            conjuncts.append(assume.translate())
        disj = "; ".join([(Expr.neg_expr(assertion)).translate() for assertion in self.asserts])
        conjuncts.append("(" + disj + ")")
        args = ", ".join([f"{var_name}" for ty_name, var_name in self.decls])
        lhs = f"{self.name}({args})"
        rhs = ", ".join(conjuncts)
        ret += f"{lhs} :- {rhs}." + "\n"
        ret += f".output {self.name}" + "\n"
        return ret


def translate(spec_path: str):
    tree = vs_parser.parse(open(spec_path, "r").read())
    prog = Prog(os.path.basename(spec_path).strip(".vs"), tree)
    return prog.translate()


if __name__ == "__main__":
    spec_path = sys.argv[1]
    print(translate(spec_path))
