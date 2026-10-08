"""
Збирає рівень насильницької злочинності по містах Іллінойсу та південно-східного Вісконсину
(до Мілвокі) з публічних даних ФБР (Crime Data Explorer) і записує app/src/main/assets/towns.tsv.

Запуск (раз на кілька місяців, перед новою версією програми):
    python tools/build_towns.py

Дані: кількість насильницьких злочинів за останні 12 місяців ÷ населення × 100 000.
Чикаго й Мілвокі мають власні детальні карти кварталів, тут вони — лише запасний варіант.
"""
import json
import math
import os
import sys
import time
import urllib.request

BASE = "https://cde.ucr.cjis.gov/LATEST"
WI_COUNTIES = {"KENOSHA", "RACINE", "MILWAUKEE", "WAUKESHA", "WALWORTH", "OZAUKEE", "WASHINGTON"}
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "towns.tsv")


def get(url, tries=4):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Oberih-builder/1.0"})
            with urllib.request.urlopen(req, timeout=40) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # noqa: BLE001
            if i == tries - 1:
                print("  ! fail", url, e, file=sys.stderr)
                return None
            time.sleep(2 + i * 3)


def agencies(state):
    data = get(f"{BASE}/agency/byStateAbbr/{state}") or {}
    out = []
    for county, items in data.items():
        for a in items:
            if a.get("agency_type_name") != "City" or a.get("latitude") is None:
                continue
            if state == "WI" and not (set((a.get("counties") or "").split(",")) & WI_COUNTIES):
                continue
            out.append(a)
    return out


def month_range():
    meta = get(f"{BASE}/summarized/agency/IL0161000/violent-crime?from=01-2024&to=12-2024&type=counts")
    last = meta["cde_properties"]["max_data_date"]["UCR"]  # "09/2026"
    m, y = map(int, last.split("/"))
    # Беремо 12 повних місяців, що закінчуються за місяць до останнього (останній часто неповний).
    end_m, end_y = (m - 1, y) if m > 1 else (12, y - 1)
    start_m, start_y = end_m + 1, end_y - 1
    if start_m == 13:
        start_m, start_y = 1, end_y
    return f"{start_m:02d}-{start_y}", f"{end_m:02d}-{end_y}"


def main():
    frm, to = month_range()
    print("period", frm, "→", to)
    ags = agencies("IL") + agencies("WI")
    print("agencies:", len(ags))
    rows = []
    for i, a in enumerate(ags):
        ori = a["ori"]
        d = get(f"{BASE}/summarized/agency/{ori}/violent-crime?from={frm}&to={to}&type=counts")
        time.sleep(0.25)
        if not d:
            continue
        name = a["agency_name"]
        actual = next((v for k, v in d.get("offenses", {}).get("actuals", {}).items() if k.endswith("Offenses")), None)
        pops = d.get("populations", {}).get("population", {})
        pop = next((v for k, v in pops.items() if k not in ("Illinois", "Wisconsin", "United States")), None)
        if not actual or not pop:
            continue
        months = [v for v in actual.values() if v is not None]
        popv = max((p for p in pop.values() if p), default=0)
        if len(months) < 6 or popv < 1000:
            continue
        yearly = sum(months) / len(months) * 12
        rate = yearly / popv * 100_000
        # Приблизний радіус міста: передмістя ~1500 людей/км².
        radius = min(9.0, max(1.5, math.sqrt(popv / (1500 * math.pi))))
        town = name.replace(" Police Department", "").replace(" Police Dept", "").strip()
        rows.append((town, a["state_abbr"], round(a["latitude"], 5), round(a["longitude"], 5), popv, round(rate), round(radius, 2), ori))
        if i % 50 == 0:
            print(f"  {i}/{len(ags)} {town}: {round(rate)} per 100k")
    rows.sort(key=lambda r: (r[1], r[0]))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# Violent crime per 100k, FBI CDE, {frm}..{to}\n")
        f.write("town\tstate\tlat\tlon\tpopulation\trate\tradius_km\tori\n")
        for r in rows:
            f.write("\t".join(map(str, r)) + "\n")
    print("written", len(rows), "towns →", OUT)


if __name__ == "__main__":
    main()
