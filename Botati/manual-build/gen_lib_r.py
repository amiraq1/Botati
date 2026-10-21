#!/usr/bin/env python3
"""Generate library R.java files: values from the linked symbol table,
entry sets from each AAR's R.txt, package from each AAR's manifest."""
import os, re, sys

BUILD = "/tmp/botati-build"
OUT = os.path.join(BUILD, "gen")

# Parse final symbols: symbols.txt lines: "int <type> <name> <value>" or
# "int[] styleable <name> { v, v, ... }" (may span lines until '}').
symbols = {}   # (type, name) -> value string
styleables = {}  # name -> [values]
text = open(os.path.join(BUILD, "symbols.txt")).read()
lines = text.splitlines()
i = 0
while i < len(lines):
    line = lines[i].strip()
    i += 1
    if not line or line.startswith("#"):
        continue
    m = re.match(r"int\[\]\s+styleable\s+(\S+)\s*\{(.*)", line)
    if m:
        name, rest = m.group(1), m.group(2)
        vals = rest
        while "}" not in vals and i < len(lines):
            vals += " " + lines[i].strip(); i += 1
        vals = vals.split("}")[0]
        styleables[name] = [v.strip() for v in vals.split(",") if v.strip()]
        continue
    m = re.match(r"int\s+(\S+)\s+(\S+)\s+(\S+)$", line)
    if m:
        symbols[(m.group(1), m.group(2))] = m.group(3)

print(f"final symbols: {len(symbols)}, styleables: {len(styleables)}")

def parse_rtxt(path):
    entries = []  # (type, name)
    styles = []   # name
    style_idx = []  # (name, index) — styleable per-attr index constants
    txt = open(path).read()
    txt = txt.replace("\n", " ")
    for m in re.finditer(r"int\[\]\s+styleable\s+(\S+)\s*\{[^}]*\}", txt):
        styles.append(m.group(1))
    txt2 = re.sub(r"int\[\]\s+styleable\s+\S+\s*\{[^}]*\}", "", txt)
    for m in re.finditer(r"int\s+(\S+)\s+(\S+)\s+(0x[0-9a-fA-F]+|\d+)", txt2):
        if m.group(1) == "styleable":
            style_idx.append((m.group(2), m.group(3)))
        else:
            entries.append((m.group(1), m.group(2)))
    return entries, styles, style_idx

generated = 0
for name in sorted(os.listdir(os.path.join(BUILD, "exploded"))):
    d = os.path.join(BUILD, "exploded", name)
    rtxt = os.path.join(d, "R.txt")
    man = os.path.join(d, "AndroidManifest.xml")
    if not os.path.exists(rtxt) or not os.path.exists(man):
        continue
    mtxt = open(man, errors="ignore").read()
    pm = re.search(r'package="([^"]+)"', mtxt)
    if not pm:
        continue
    pkg = pm.group(1)
    entries, styles, style_idx = parse_rtxt(rtxt)
    # group entries by type
    by_type = {}
    missing = 0
    for t, n in entries:
        v = symbols.get((t, n))
        if v is None:
            missing += 1
            continue
        by_type.setdefault(t, []).append((n, v))
    style_fields = {}
    for s in styles:
        vals = styleables.get(s)
        if vals is None:
            continue
        style_fields[s] = vals
    if not by_type and not style_fields:
        continue
    pkgdir = os.path.join(OUT, *pkg.split("."))
    os.makedirs(pkgdir, exist_ok=True)
    with open(os.path.join(pkgdir, "R.java"), "w") as f:
        f.write(f"package {pkg};\npublic final class R {{\n")
        for t, items in sorted(by_type.items()):
            f.write(f"  public static final class {t} {{\n")
            for n, v in items:
                f.write(f"    public static final int {n} = {v};\n")
            f.write("  }\n")
        if style_fields or style_idx:
            f.write("  public static final class styleable {\n")
            for s, vals in sorted(style_fields.items()):
                arr = ", ".join(vals)
                f.write(f"    public static final int[] {s} = new int[] {{ {arr} }};\n")
            for n, idx in style_idx:
                f.write(f"    public static final int {n} = {idx};\n")
            f.write("  }\n")
        f.write("}\n")
    generated += 1
    if missing:
        print(f"  {name}: {missing} R.txt entries not in final table (ok if pruned)")

print(f"generated {generated} library R.java files")
