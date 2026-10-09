"""Generate SevconCatalog.kt from Twizy-SDO-List.ods (exported as CSV)."""
import csv, sys

src, dst = sys.argv[1], sys.argv[2]

STRING = {(0x1008, 0), (0x1009, 0), (0x100A, 0)}
U32 = {(0x1018, 2), (0x1018, 3), (0x6075, 0), (0x6076, 0), (0x2916, 1)}
SIGNED = {(0x2870, 3), (0x2870, 7), (0x4623, 1), (0x2872, 2)}
# Objects OVMS writes in operational mode (cfg_drive/recup/ramps/rampl/smoothing).
OP_WRITABLE = {(0x2920, s) for s in (1, 3, 4, 7, 0x0B, 0x0D, 0x0E, 0x0F, 0x10)} | {
    (0x291C, 1), (0x291C, 2), (0x290A, 1), (0x290A, 2), (0x290A, 3)}

# unit, scale (display = raw * scale)
def unit_of(idx, sub, name):
    if idx in (0x4611, 0x4610) and sub % 2 == 1 and "torque" in name.lower():
        return "Nm", "1.0 / 16"
    if idx == 0x4611:
        return "rpm", "1.0"
    if "(mNm)" in name: return "Nm", "0.001"
    if "(mA)" in name: return "A", "0.001"
    if "(A)" in name: return "A", "1.0"
    if "(%)" in name: return "%", "1.0"
    if "rpm" in name.lower(): return "rpm", "1.0"
    if idx == 0x2920 and sub in (1, 3, 4): return "%", "0.1"
    return "", "1.0"

def num(v):
    v = v.strip()
    if not v: return None
    try:
        return int(v, 16) if v.lower().startswith("0x") else int(v)
    except ValueError:
        return None

def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ").strip() + '"'

seen, out, group = set(), [], "General"
with open(src, newline="") as f:
    rows = list(csv.reader(f))[1:]
last_name = ""
for r in rows:
    if not r[0].strip():
        continue
    idx, sub = int(r[0], 16), int(r[1], 16)
    if (idx, sub) in seen:
        continue
    seen.add((idx, sub))
    name, remark, macro = r[4].strip(), r[5].strip(), r[7].strip()
    if name.startswith("…"):
        # "… current 2" continues the previous block's name
        base = last_name.split(":")[0].split(" curve")[0] if last_name else ""
        name = (base + " " + name[1:].strip()).strip()
    else:
        last_name = name
    if (idx, sub) in STRING: typ = "STRING"
    elif (idx, sub) in U32: typ = "U32"
    elif (idx, sub) in SIGNED: typ = "S16"
    else: typ = "U16"
    d80, d45 = (None, None) if typ == "STRING" else (num(r[2]), num(r[3]))
    unit, scale = unit_of(idx, sub, name)
    out.append(
        f"        param(0x{idx:04X}, 0x{sub:02X}, {kstr(name)}, DataType.{typ}, "
        f"default80 = {d80 if d80 is not None else 'null'}, default45 = {d45 if d45 is not None else 'null'}, "
        f"unit = {kstr(unit)}, scale = {scale}, "
        f"preOp = {'false' if (idx, sub) in OP_WRITABLE else 'true'}, "
        f"tags = {kstr(macro)}, remark = {kstr(remark)}),")

with open(dst, "w") as f:
    f.write('''// GENERATED from Twizy-SDO-List.ods by tools/gen_catalog.py — do not edit by hand.
// Edit write limits in SafetyLimits.kt instead.
package de.dressler.twizy.core.params

internal object GeneratedCatalog {
    val entries: List<Parameter> = listOf(
''' + "\n".join(out) + '''
    )
}
''')
print(len(out), "entries")
