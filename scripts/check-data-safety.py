#!/usr/bin/env python3
"""Check a Google Play Data safety CSV for answers the Play upload rejects.

Usage: scripts/check-data-safety.py [play/data-safety.csv]
"""

import collections
import csv
import sys

COLUMNS = 5
ACCOUNT_CREATION = "PSL_SUPPORTED_ACCOUNT_CREATION_METHODS"
NO_ACCOUNT_CREATION = "PSL_ACM_NONE"
ACCOUNT_DELETION_URL = "PSL_ACCOUNT_DELETION_URL"


def problems(rows):
    single_choice = collections.defaultdict(list)
    account_creation = []
    deletion_url_lines = []
    for line, row in rows:
        if len(row) != COLUMNS:
            yield f"line {line}: {len(row)} columns, expected {COLUMNS}"
            continue
        question, response, value, requirement, _ = row
        chosen = value.strip().upper() == "TRUE"
        if requirement == "SINGLE_CHOICE" and chosen:
            single_choice[question].append(line)
        if question == ACCOUNT_CREATION and chosen:
            account_creation.append(response)
        if question == ACCOUNT_DELETION_URL and value.strip():
            deletion_url_lines.append(line)
    for question, lines in single_choice.items():
        if len(lines) > 1:
            yield f"{question}: single choice answered TRUE on lines {', '.join(map(str, lines))}"
    if NO_ACCOUNT_CREATION in account_creation and len(account_creation) > 1:
        yield f"{ACCOUNT_CREATION}: {NO_ACCOUNT_CREATION} is TRUE next to {', '.join(sorted(set(account_creation) - {NO_ACCOUNT_CREATION}))}"
    if deletion_url_lines and set(account_creation) <= {NO_ACCOUNT_CREATION}:
        yield f"line {deletion_url_lines[0]}: {ACCOUNT_DELETION_URL} must be empty without account creation"


def main(path):
    with open(path, newline="", encoding="utf-8") as file:
        reader = csv.reader(file)
        rows = [(reader.line_num, row) for row in reader]
    found = list(problems(rows))
    for problem in found:
        print(f"{path}: {problem}", file=sys.stderr)
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "play/data-safety.csv"))
