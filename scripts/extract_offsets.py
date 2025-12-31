#!/usr/bin/env python3
"""
Extract patch offsets from Il2CppDumper output.
Usage: python extract_offsets.py dump.cs > patches/X.XX.json
"""
import re
import sys
import json

def extract_offsets(dump_file):
    with open(dump_file, 'r') as f:
        content = f.read()

    patches = []
    
    # MonetizationVars getters
    iap_offsets = []
    pattern = r'// RVA: (0x[A-Fa-f0-9]+).*?\n\t(?:public |private )?bool get_(UserBought|UserGiven)[^(]+\('
    for match in re.finditer(pattern, content):
        iap_offsets.append(match.group(1))
    
    if iap_offsets:
        patches.append({
            "name": "Unlock All IAPs",
            "description": "Unlocks all in-app purchases",
            "offsets": iap_offsets,
            "patch": "return_true"
        })

    # BitPass
    bitpass = re.search(r'// RVA: (0x[A-Fa-f0-9]+).*?\n\t.*?get_HasBitPass', content)
    if bitpass:
        patches.append({
            "name": "Unlock BitPass",
            "description": "Unlocks BitPass subscription",
            "offsets": [bitpass.group(1)],
            "patch": "return_true"
        })

    # CanUseItem
    item_offsets = []
    for match in re.finditer(r'// RVA: (0x[A-Fa-f0-9]+).*?\n\t.*?CanUseItem', content):
        item_offsets.append(match.group(1))
    if item_offsets:
        patches.append({
            "name": "Unlock Items",
            "description": "Use any item",
            "offsets": item_offsets,
            "patch": "return_true"
        })

    # IsStreakJeopardy
    streak = re.search(r'// RVA: (0x[A-Fa-f0-9]+).*?\n\t.*?IsStreakJeopardy', content)
    if streak:
        patches.append({
            "name": "No Streak Loss",
            "description": "Never lose streaks",
            "offsets": [streak.group(1)],
            "patch": "return_false"
        })

    return patches

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python extract_offsets.py dump.cs [version]", file=sys.stderr)
        sys.exit(1)
    
    version = sys.argv[2] if len(sys.argv) > 2 else "unknown"
    patches = extract_offsets(sys.argv[1])
    
    output = {
        "version": version,
        "app": "com.candywriter.bitlife",
        "patches": patches
    }
    
    print(json.dumps(output, indent=2))
