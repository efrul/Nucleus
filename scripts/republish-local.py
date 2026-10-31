#!/usr/bin/env python3
"""Copies published Nucleus artifacts into Maven Local under another version.

A fork that changes one module publishes that module from source under its own
version; an app that pins every Nucleus module to one version then needs the
others under that version as well. This copies them from the published release,
byte for byte, rewriting only the version in their .pom and .module files, and
follows their dependencies on other Nucleus artifacts of the same release (KMP
`available-at` redirects included).

    scripts/republish-local.py --from 3.0.0-dev-202610272317 --to 3.0.0-dev-202610272317-1-tearoff \
        --skip nucleus.decorated-window-tao nucleus.core-runtime dev.nucleusframework.gradle.plugin ...

Artifacts named in --skip are left alone (the ones published from source), but
their published dependencies are still followed.
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

GROUP = "dev.nucleusframework"
DEFAULT_REPO = "https://repo1.maven.org/maven2"


def fetch(url):
    try:
        with urllib.request.urlopen(url, timeout=120) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--from", dest="source", required=True, help="the published version")
    parser.add_argument("--to", dest="target", required=True, help="the version to copy it to")
    parser.add_argument("--repo", default=os.environ.get("NUCLEUS_SOURCE_REPO", DEFAULT_REPO))
    parser.add_argument("--local", default=os.path.expanduser("~/.m2/repository"))
    parser.add_argument("--skip", nargs="*", default=[], help="artifacts published from source")
    parser.add_argument("artifacts", nargs="+", help="artifact ids to start from (group " + GROUP + ")")
    args = parser.parse_args()

    group_path = GROUP.replace(".", "/")
    pending = list(args.artifacts)
    seen = set()
    copied = []
    while pending:
        artifact = pending.pop()
        if artifact in seen:
            continue
        seen.add(artifact)
        base = f"{args.repo}/{group_path}/{artifact}/{args.source}/{artifact}-{args.source}"
        pom = fetch(base + ".pom")
        if pom is None:
            sys.exit(f"{artifact}:{args.source} is not published at {args.repo}")
        module = fetch(base + ".module")
        pom_text = pom.decode("utf-8")
        module_text = module.decode("utf-8") if module is not None else None

        # Dependencies on the same release, from the POM and from every variant of the module.
        deps = set(
            re.findall(
                r"<groupId>" + re.escape(GROUP) + r"</groupId>\s*<artifactId>([^<]+)</artifactId>\s*"
                r"<version>" + re.escape(args.source) + r"</version>",
                pom_text,
            )
        )
        files = set()
        if module_text is not None:
            for variant in json.loads(module_text).get("variants", []):
                for dep in variant.get("dependencies", []):
                    version = dep.get("version", {})
                    if dep.get("group") == GROUP and args.source in version.values():
                        deps.add(dep["module"])
                redirect = variant.get("available-at")
                if redirect and redirect.get("group") == GROUP and redirect.get("version") == args.source:
                    deps.add(redirect["module"])
                for f in variant.get("files", []):
                    files.add(f["url"])
        else:
            packaging = re.search(r"<packaging>([^<]+)</packaging>", pom_text)
            if packaging is None or packaging.group(1) not in ("pom",):
                files.add(f"{artifact}-{args.source}.jar")
        pending.extend(sorted(deps - seen))
        if artifact in args.skip:
            continue

        out_dir = os.path.join(args.local, group_path, artifact, args.target)
        os.makedirs(out_dir, exist_ok=True)

        def target_name(name):
            return name.replace(f"{artifact}-{args.source}", f"{artifact}-{args.target}")

        with open(os.path.join(out_dir, f"{artifact}-{args.target}.pom"), "w", encoding="utf-8") as out:
            out.write(pom_text.replace(args.source, args.target))
        if module_text is not None:
            with open(os.path.join(out_dir, f"{artifact}-{args.target}.module"), "w", encoding="utf-8") as out:
                out.write(module_text.replace(args.source, args.target))
        for name in sorted(files):
            data = fetch(f"{args.repo}/{group_path}/{artifact}/{args.source}/{name}")
            if data is None:
                sys.exit(f"{artifact}: {name} is listed but not published")
            with open(os.path.join(out_dir, target_name(name)), "wb") as out:
                out.write(data)
        copied.append(artifact)

    print(f"copied {len(copied)} artifacts from {args.source} to {args.target}:")
    for artifact in sorted(copied):
        print("  " + artifact)


if __name__ == "__main__":
    main()
