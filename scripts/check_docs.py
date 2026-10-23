#!/usr/bin/env python3
"""检查 README 与计划文档引用的本地文件是否存在。"""

from pathlib import Path
import re
import sys


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    sources = [root / "README.md", root / "docs/wiki/Implementation-Plan.md"]
    missing: list[str] = []
    for source in sources:
        text = source.read_text(encoding="utf-8")
        for target in re.findall(r"\]\(([^)#]+)", text):
            if "://" in target:
                continue
            if not (source.parent / target).resolve().exists():
                missing.append(f"{source}: {target}")
    if missing:
        print("缺少文档引用目标:")
        print("\n".join(missing))
        return 1
    print("文档引用检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
