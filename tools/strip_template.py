"""Turn the original Formulaire workbook into the template the app ships.

The app fills the form by copying every zip entry of a template verbatim and
rewriting only the cell values in xl/worksheets/sheet1.xml. That stays safe as
long as the template needs no structural surgery at export time -- so all of
the structural surgery happens here, once, offline, and the result is committed
as app/src/main/assets/formulaire_template.xlsx.

What this removes, and why:

  * the "carte de kilometrage" sheet (3.1 MB of distance matrix) and its copy
    of the logo. The app already knows each trip's distance, so it writes the
    number straight into column L instead of letting Excel look it up.
  * every formula that depended on that sheet: the hidden P/Q/R helpers, and
    the L (km) and N (total) formulas that read them. The app writes L and N
    as literal numbers.
  * the four dropdowns, which pointed at named ranges on the deleted sheet.
  * personal data: the employee header the board pre-filled (C13 name, J13
    employee number, C14-C16 address), shared strings no kept cell uses (the
    matrix sheet's list held people's home addresses), the author in
    docProps/core.xml, and the logo's alt text (a SharePoint URL carrying an
    access token).

What this deliberately keeps:

  * the logo, fonts, fills, borders, merged cells, row heights, column widths
    and print setup -- untouched, so a printed export is indistinguishable from
    a printed blank.
  * the totals in M48/D50/M50/M54. They only reference cells on the form
    itself, so they still compute from whatever the app writes into N.

Usage:  python tools/strip_template.py <original workbook.xlsx> [output.xlsx]

The output defaults to the committed asset; pass a second path to write a
copy elsewhere (for a dry run) and leave the asset alone.
"""

import re
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "app/src/main/assets/formulaire_template.xlsx"

# Zip entries belonging to the distance-matrix sheet.
DROP_ENTRIES = {
    "xl/worksheets/sheet2.xml",
    "xl/worksheets/_rels/sheet2.xml.rels",
    "xl/drawings/drawing2.xml",
    "xl/drawings/_rels/drawing2.xml.rels",
    "xl/media/image2.png",
    "xl/calcChain.xml",  # stale after we change the sheet; Excel rebuilds it
}

FIRST_TRIP_ROW = 23
LAST_TRIP_ROW = 47

# The employee header: name, employee number and address. Each user fills
# these in, so the template ships them blank (styles kept).
HEADER_CELLS = {"C13", "J13", "C14", "C15", "C16"}

CELL_RE = re.compile(r'<c r="([A-Z]+)(\d+)"([^>]*?)(/>|>.*?</c>)', re.S)


def blank_computed_cells(xml: str) -> str:
    """Drop every formula that depended on the matrix sheet, and the employee
    header, keeping styles.

    A cell is emptied by collapsing it to its self-closing form, which keeps its
    r and s attributes -- and therefore its borders, fill and number format --
    while discarding the formula and any cached value.
    """

    def repl(m: "re.Match[str]") -> str:
        col, row, attrs = m.group(1), int(m.group(2)), m.group(3)
        # Style only; t="str" described the cached formula result and must go.
        attrs = re.sub(r'\s+t="[^"]*"', "", attrs)
        helper = col in ("P", "Q", "R") and FIRST_TRIP_ROW <= row <= 54
        computed = col in ("L", "N") and FIRST_TRIP_ROW <= row <= LAST_TRIP_ROW
        header = "%s%d" % (col, row) in HEADER_CELLS
        if helper or computed or header:
            return '<c r="%s%d"%s/>' % (col, row, attrs)
        return m.group(0)

    return CELL_RE.sub(repl, xml)


def blank_unused_strings(xml: str, sheet: str) -> str:
    """Empty every shared string no cell of the kept sheet points at.

    Entries are blanked in place, not removed, so the indices the sheet uses
    stay valid.
    """
    used = {int(i) for i in re.findall(r'<c [^>]*t="s"[^>]*>(?:<f>.*?</f>)?<v>(\d+)</v>', sheet, re.S)}
    index = iter(range(1 << 30))

    def repl(m: "re.Match[str]") -> str:
        return m.group(0) if next(index) in used else "<si><t></t></si>"

    return re.sub(r"<si>.*?</si>", repl, xml, flags=re.S)


def main() -> int:
    if len(sys.argv) not in (2, 3):
        print("usage: python tools/strip_template.py <original workbook.xlsx> [output.xlsx]", file=sys.stderr)
        return 2
    source = Path(sys.argv[1]).resolve()
    target = Path(sys.argv[2]).resolve() if len(sys.argv) == 3 else TARGET
    if not source.exists():
        print("missing source workbook: %s" % source, file=sys.stderr)
        return 1
    target.parent.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(source) as src:
        names = src.namelist()
        kept_sheet = blank_computed_cells(src.read("xl/worksheets/sheet1.xml").decode("utf-8"))
        missing = DROP_ENTRIES - set(names) - {"xl/calcChain.xml"}
        if missing:
            print("source workbook is not the expected one, missing %s" % missing, file=sys.stderr)
            return 1

        tmp = target.with_suffix(".tmp")
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as out:
            for name in names:
                if name in DROP_ENTRIES:
                    continue
                data = src.read(name)

                if name == "xl/worksheets/sheet1.xml":
                    xml = data.decode("utf-8")
                    xml = re.sub(r"<dataValidations>.*?</dataValidations>", "", xml, flags=re.S)
                    xml = blank_computed_cells(xml)
                    data = xml.encode("utf-8")

                elif name == "xl/workbook.xml":
                    xml = data.decode("utf-8")
                    # Attribute order varies, so match the name wherever it sits,
                    # and stop before the accent so the pattern stays ASCII.
                    xml = re.sub(r'<sheet\b[^>]*name="carte de kilom[^"]*"[^>]*/>', "", xml)
                    xml = re.sub(r"<definedNames>.*?</definedNames>", "", xml, flags=re.S)
                    data = xml.encode("utf-8")

                elif name == "xl/_rels/workbook.xml.rels":
                    xml = data.decode("utf-8")
                    xml = re.sub(r'<Relationship[^>]*Target="worksheets/sheet2\.xml"[^>]*/>', "", xml)
                    data = xml.encode("utf-8")

                elif name == "xl/sharedStrings.xml":
                    data = blank_unused_strings(data.decode("utf-8"), kept_sheet).encode("utf-8")

                elif name == "docProps/core.xml":
                    xml = data.decode("utf-8")
                    for tag in ("dc:creator", "cp:lastModifiedBy"):
                        xml = re.sub(r"<%s>.*?</%s>|<%s/>" % (tag, tag, tag), "", xml, flags=re.S)
                    data = xml.encode("utf-8")

                elif name.startswith("xl/drawings/drawing") and name.endswith(".xml"):
                    xml = data.decode("utf-8")
                    xml = re.sub(r'descr="[^"]*"', 'descr="Logo"', xml)
                    data = xml.encode("utf-8")

                elif name == "[Content_Types].xml":
                    xml = data.decode("utf-8")
                    for part in ("/xl/worksheets/sheet2.xml", "/xl/drawings/drawing2.xml", "/xl/calcChain.xml"):
                        xml = re.sub(r'<Override\b[^>]*PartName="%s"[^>]*/>' % re.escape(part), "", xml)
                    data = xml.encode("utf-8")

                # A fixed timestamp keeps reruns reproducible, so regenerating
                # the asset from the same source gives the same entries and dates.
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                out.writestr(info, data)

        shutil.move(str(tmp), str(target))

    verify(target)

    before = source.stat().st_size
    after = target.stat().st_size
    print("wrote %s  %s -> %s bytes" % (target, format(before, ","), format(after, ",")))
    return 0


def verify(target: Path) -> None:
    """Fail loudly rather than shipping a template Excel would offer to repair."""
    with zipfile.ZipFile(target) as z:
        names = set(z.namelist())
        workbook = z.read("xl/workbook.xml").decode("utf-8")
        rels = z.read("xl/_rels/workbook.xml.rels").decode("utf-8")
        sheet = z.read("xl/worksheets/sheet1.xml").decode("utf-8")
        content_types = z.read("[Content_Types].xml").decode("utf-8")
        everything = b"".join(z.read(n) for n in names if n.endswith((".xml", ".rels"))).decode("utf-8").lower()

    problems = []
    if "carte de kilom" in workbook:
        problems.append("the matrix sheet is still listed in workbook.xml")
    if "carte de kilom" in sheet:
        problems.append("sheet1.xml still references the matrix sheet")
    if "dataValidations" in sheet:
        problems.append("the dropdowns survived")
    for part in ("sheet2", "drawing2"):
        if part in content_types:
            problems.append("[Content_Types].xml still declares %s" % part)
    for leak in ("maison ", "access_token", "sharepoint", "dc:creator>", "lastmodifiedby>"):
        if leak in everything:
            problems.append("personal data survived: %r" % leak)
    for ref in sorted(HEADER_CELLS):
        cell = re.search(r'<c r="%s"[^>]*?(/>|>.*?</c>)' % ref, sheet, re.S)
        if cell and cell.group(1) != "/>":
            problems.append("header cell %s still has a value" % ref)
    if names & DROP_ENTRIES:
        problems.append("dropped parts are still in the zip: %s" % (names & DROP_ENTRIES))

    # Every r:id the workbook points at must still resolve, or Excel reports a
    # damaged file. This is what catches a sheet entry whose relationship was
    # removed -- the exact failure this script hit while being written.
    declared = set(re.findall(r'Id="([^"]+)"', rels))
    referenced = set(re.findall(r'r:id="([^"]+)"', workbook))
    dangling = referenced - declared
    if dangling:
        problems.append("workbook.xml points at missing relationships: %s" % sorted(dangling))

    if problems:
        raise SystemExit("template verification failed:\n  - " + "\n  - ".join(problems))


if __name__ == "__main__":
    raise SystemExit(main())
