#!/usr/bin/env python3
"""catdir: concatenate (or summarize, or JSON-export, or rebuild) a directory tree.

Python port of the original catdir bash script, with proper directory exclusion.

Ignore patterns (-i) are matched against every path component AND every relative
path prefix, so all of these work:

    -i out                      any file or directory named "out"
    -i 'results3-*'             any file or directory matching the glob
    -i 'iperf3-results/**'      the directory iperf3-results and everything in it
    -i 'bench/ssh'              a specific relative path
    -i '*.log'                  files by glob

Matched directories are pruned, so they are never descended into.
Quote globs in your shell so the shell does not expand them first.
"""
import argparse
import fnmatch
import hashlib
import json
import os
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

TEXT_SUFFIXES = (
    ".txt", ".md", ".markdown", ".rst", ".csv", ".json", ".yaml", ".yml",
    ".toml", ".ini", ".cfg", ".conf", ".sh", ".bash", ".zsh", ".fish",
    ".py", ".rb", ".pl", ".php", ".lua", ".js", ".ts", ".jsx", ".tsx",
    ".c", ".h", ".cpp", ".cc", ".cxx", ".hpp", ".cs", ".java", ".go", ".rs",
    ".html", ".htm", ".css", ".scss", ".sass", ".less", ".xml", ".svg",
    ".sql", ".r", ".m", ".swift", ".kt", ".scala", ".ex", ".exs",
    ".tf", ".hcl", ".mk", ".cshtml", ".vbhtml", ".razor", ".aspx", ".ascx",
    ".jsp", ".ejs", ".hbs", ".handlebars", ".pug", ".slim", ".haml", ".erb",
    ".liquid", ".twig", ".env", ".gitignore", ".dockerignore",
    ".ps1", ".psm1", ".psd1", ".bat", ".cmd", ".awk", ".sed",
    ".vim", ".lisp", ".clj", ".cljs", ".elm", ".erl", ".hrl",
    ".hs", ".ml", ".mli", ".nim", ".pas", ".pp", ".pro", ".proto",
    ".rkt", ".scm", ".tcl", ".zig", ".tex", ".sty", ".cls", ".bib",
    ".nix", ".dhall",
)


# ----------------------------------------------------------------------
# Ignore handling
# ----------------------------------------------------------------------
def normalize_pattern(pattern):
    p = pattern.strip()
    while p.startswith("./"):
        p = p[2:]
    # "dir/**" and "dir/" both mean "the directory dir and everything below it"
    while p.endswith("/**") or p.endswith("/"):
        p = p[:-3] if p.endswith("/**") else p[:-1]
    return p


class Ignorer:
    def __init__(self, patterns):
        self.patterns = [p for p in (normalize_pattern(x) for x in patterns) if p]

    def matches(self, rel):
        """rel is a path relative to the target dir, using '/' separators."""
        if not self.patterns:
            return False
        parts = rel.split("/")
        for i, part in enumerate(parts):
            prefix = "/".join(parts[: i + 1])
            for pat in self.patterns:
                if fnmatch.fnmatchcase(part, pat) or fnmatch.fnmatchcase(prefix, pat):
                    return True
        return False


# ----------------------------------------------------------------------
# File selection
# ----------------------------------------------------------------------
def make_ext_filter(extension, glob_mode):
    if not extension:
        return lambda name: True
    if glob_mode:
        return lambda name: fnmatch.fnmatch(name, extension)
    bare = extension
    if bare.startswith("*"):
        bare = bare[1:]
    if bare.startswith("."):
        bare = bare[1:]
    suffix = "." + bare
    return lambda name: name.endswith(suffix)


def iter_files(target, max_depth, ignorer, ext_ok, skip_abs):
    """Yield file paths (prefixed with target, like find does)."""
    for dirpath, dirnames, filenames in os.walk(target):
        rel_dir = os.path.relpath(dirpath, target)
        rel_dir = "" if rel_dir == "." else rel_dir.replace(os.sep, "/")
        depth = rel_dir.count("/") + 1 if rel_dir else 0

        keep = []
        for d in dirnames:
            rel = f"{rel_dir}/{d}" if rel_dir else d
            if d == ".git" or ignorer.matches(rel):
                continue
            keep.append(d)
        if max_depth is not None and depth + 1 >= max_depth:
            keep = []
        dirnames[:] = keep

        if max_depth is not None and depth + 1 > max_depth:
            continue

        for name in filenames:
            rel = f"{rel_dir}/{name}" if rel_dir else name
            if ignorer.matches(rel) or not ext_ok(name):
                continue
            full = os.path.join(dirpath, name)
            if os.path.islink(full) or not os.path.isfile(full):
                continue
            if skip_abs and os.path.abspath(full) == skip_abs:
                continue
            yield full


def build_engine_cmd(engine, args, ignorer):
    """Build the fd or rg command line. Native excludes are only a speed-up;
    results are re-filtered in Python so every engine returns the same set."""
    extension = args.ext
    bare = extension[1:] if extension.startswith("*") else extension
    bare = bare[1:] if bare.startswith(".") else bare

    if engine == "rg":
        cmd = ["rg", "--files", "--hidden", "--no-ignore", "-0"]
        if args.maxdepth is not None:
            cmd += ["--max-depth", str(args.maxdepth)]
        if extension:
            cmd += ["-g", extension if args.match else f"*.{bare}"]
        for pat in ignorer.patterns:
            cmd += ["-g", f"!{pat}"]
        cmd += ["-g", "!.git", "--", args.target]
        return cmd

    cmd = ["fd", "--type", "f", "--hidden", "--no-ignore", "-0", "--exclude", ".git"]
    if args.maxdepth is not None:
        cmd += ["--max-depth", str(args.maxdepth)]
    for pat in ignorer.patterns:
        cmd += ["--exclude", pat]
    if extension and args.match:
        cmd += ["--glob", extension]
    elif extension:
        cmd += ["-e", bare, "."]
    else:
        cmd += ["."]
    cmd += [args.target]
    return cmd


def iter_files_external(engine, args, ignorer, ext_ok, skip_abs):
    """Return a list of files from fd/rg, or None if the engine is unusable."""
    if shutil.which(engine) is None:
        eprint(f"Warning: '{engine}' not found, falling back to the built-in walker.")
        return None
    try:
        proc = subprocess.run(build_engine_cmd(engine, args, ignorer),
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    except OSError as exc:
        eprint(f"Warning: could not run '{engine}' ({exc}), falling back to the built-in walker.")
        return None
    # rg exits 1 when nothing matched, which is not an error
    if proc.returncode not in (0, 1):
        eprint(f"Warning: '{engine}' failed (exit {proc.returncode}), falling back to the built-in walker.")
        return None

    result = []
    for raw in proc.stdout.split(b"\0"):
        if not raw:
            continue
        rel = os.path.relpath(os.fsdecode(raw), args.target).replace(os.sep, "/")
        parts = rel.split("/")
        if ".git" in parts or ignorer.matches(rel) or not ext_ok(parts[-1]):
            continue
        if args.maxdepth is not None and len(parts) > args.maxdepth:
            continue
        full = os.path.join(args.target, rel)
        if os.path.islink(full) or not os.path.isfile(full):
            continue
        if skip_abs and os.path.abspath(full) == skip_abs:
            continue
        result.append(full)
    return result


def select_files(args, ignorer, skip_abs):
    ext_ok = make_ext_filter(args.ext, args.match)
    files = None
    engine = "rg" if args.rg else ("fd" if args.fd else None)
    if engine:
        files = iter_files_external(engine, args, ignorer, ext_ok, skip_abs)
    if files is None:
        files = iter_files(args.target, args.maxdepth, ignorer, ext_ok, skip_abs)
    if args.ordered == "alpha":
        return sorted(files)
    if args.ordered == "mtime":
        return sorted(files, key=lambda f: os.path.getmtime(f))
    return files


# ----------------------------------------------------------------------
# Rendering helpers
# ----------------------------------------------------------------------
def is_binary(path):
    lower = os.path.basename(path).lower()
    if lower.endswith(TEXT_SUFFIXES) or "dockerfile" in lower or "makefile" in lower:
        return False
    try:
        with open(path, "rb") as fh:
            return b"\x00" in fh.read(8192)
    except OSError:
        return True


def sha256_of(path):
    h = hashlib.sha256()
    try:
        with open(path, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                h.update(chunk)
    except OSError:
        return "hash_error"
    return h.hexdigest()


def make_header(label_text, width):
    label = f" {label_text} "
    pad_len = max((width - len(label)) // 2, 0)
    pad = "=" * pad_len
    right = pad + "=" if pad_len * 2 + len(label) < width else pad
    return f"{pad}{label}{right}"


def read_text(path):
    with open(path, "rb") as fh:
        return fh.read().decode("utf-8", errors="replace")


def eprint(*a):
    print(*a, file=sys.stderr)


def process_file(path, args, out, width):
    """Return a JSON record (dict) in json mode, otherwise write text to out."""
    if args.size is not None:
        try:
            fsize = os.path.getsize(path)
        except OSError:
            eprint(f"  [warning: could not determine size for {path}, skipping]")
            return None
        if fsize > args.size:
            eprint(f"  [skipped: {path} ({fsize} bytes > limit {args.size})]")
            return None
    if is_binary(path):
        eprint(f"  [skipped: {path} (binary)]")
        return None

    try:
        content = read_text(path)
    except OSError as exc:
        eprint(f"  [warning: could not read {path}: {exc}]")
        return None

    if args.output_format == "json":
        rec = {"file": path}
        if args.hash:
            rec["hash"] = sha256_of(path)
        rec["permissions"] = f"{os.stat(path).st_mode & 0o777:o}"
        rec["content"] = content
        return rec

    out.write(make_header(path, width) + "\n")
    if args.hash:
        out.write(f"SHA256 Hash: {sha256_of(path)}\n")
        out.write("-" * 40 + "\n")
    if not content.endswith("\n") and content:
        content += "\n"
    if args.markdown:
        base = os.path.basename(path)
        ext = base.rsplit(".", 1)[1] if "." in base else "txt"
        out.write(f"```{ext}\n{content}\n```\n")
    else:
        out.write(content)
    out.write("\n")
    return True


# ----------------------------------------------------------------------
# Modes
# ----------------------------------------------------------------------
def run_concat(args, ignorer):
    out_path = args.output
    out = open(out_path, "w", encoding="utf-8", newline="") if out_path else sys.stdout
    if not out_path:
        try:
            sys.stdout.reconfigure(errors="replace")
        except Exception:
            pass
    skip_abs = os.path.abspath(out_path) if out_path else None
    width = 80
    if out is sys.stdout and sys.stdout.isatty():
        width = shutil.get_terminal_size((80, 24)).columns

    found = 0
    json_mode = args.output_format == "json"
    try:
        if json_mode:
            out.write("[")
        first = True
        for path in select_files(args, ignorer, skip_abs):
            found += 1
            result = process_file(path, args, out, width)
            if json_mode and result:
                out.write(("" if first else ",") + "\n  " +
                          json.dumps(result, ensure_ascii=False))
                first = False
        if json_mode:
            out.write("\n]\n")
    finally:
        if out_path:
            out.close()

    if found == 0:
        eprint(f"catdir: no matching files found in '{args.target}'.")
    if out_path:
        label = "JSON" if json_mode else "into"
        eprint(f"Done. All files concatenated {label}: {out_path}")
    return 0


def run_tree(args, ignorer):
    skip_abs = os.path.abspath(args.output) if args.output else None
    files = list(select_files(args, ignorer, skip_abs))

    def fsize(p):
        try:
            return os.path.getsize(p)
        except OSError:
            return 0

    if args.threads > 1:
        with ThreadPoolExecutor(max_workers=args.threads) as ex:
            total = sum(ex.map(fsize, files))
    else:
        total = sum(fsize(p) for p in files)

    tree = {}
    for p in files:
        rel = os.path.relpath(p, args.target).replace(os.sep, "/")
        node = tree
        parts = rel.split("/")
        for part in parts[:-1]:
            node = node.setdefault(part, {})
        node[parts[-1]] = None

    lines = ["=== Tree Summary ===", f"File count: {len(files)}",
             f"Total bytes: {total}", "-------------------", args.target]

    def render(node, prefix):
        names = sorted(node, key=lambda n: (node[n] is None, n))
        for i, name in enumerate(names):
            last = i == len(names) - 1
            lines.append(f"{prefix}{'└── ' if last else '├── '}{name}")
            if node[name] is not None:
                render(node[name], prefix + ("    " if last else "│   "))

    render(tree, "")
    text = "\n".join(lines) + "\n"
    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(text)
    else:
        sys.stdout.write(text)
    return 0


def run_reconstruct(args):
    src = args.reconstruct
    if not os.path.isfile(src):
        eprint(f"Error: JSON input file not found: '{src}'")
        return 1
    try:
        with open(src, "r", encoding="utf-8") as fh:
            records = json.load(fh)
    except json.JSONDecodeError:
        eprint(f"Error: '{src}' is not valid JSON.")
        return 1
    if isinstance(records, dict):
        records = [records]

    dest_root = args.reconstruct_dir
    eprint(f"=== Reconstructing tree from: {src} ===")
    eprint(f"=== Target directory: {dest_root} ===")

    total = written = skipped = failed = verified = verify_failed = 0
    for rec in records:
        filepath = (rec.get("file") or "") if isinstance(rec, dict) else ""
        if not filepath:
            eprint("  [warning: record missing 'file' field, skipping]")
            failed += 1
            continue
        rel = filepath
        while rel.startswith("./"):
            rel = rel[2:]
        rel = rel.lstrip("/")
        if ".." in rel.split("/"):
            eprint(f"  [error: refusing path with '..': '{filepath}']")
            failed += 1
            continue
        dest = os.path.join(dest_root, rel)
        total += 1

        if os.path.exists(dest) and not args.force:
            eprint(f"  [skipped: '{dest}' already exists (use --force to overwrite)]")
            skipped += 1
            continue
        try:
            os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
            with open(dest, "wb") as fh:
                fh.write((rec.get("content") or "").encode("utf-8"))
        except OSError as exc:
            eprint(f"  [error: could not write '{dest}': {exc}]")
            failed += 1
            continue

        perms = rec.get("permissions")
        if perms:
            try:
                os.chmod(dest, int(perms, 8))
            except (ValueError, OSError):
                eprint(f"  [warning: could not set permissions {perms} on '{dest}']")

        if args.verify_hashes:
            stored = rec.get("hash")
            if not stored:
                eprint(f"  [warning: no hash stored for '{filepath}', skipping verification]")
            else:
                actual = sha256_of(dest)
                if actual == stored:
                    verified += 1
                else:
                    eprint(f"  [HASH MISMATCH: '{dest}']")
                    eprint(f"    stored:   {stored}")
                    eprint(f"    computed: {actual}")
                    verify_failed += 1

        eprint(f"  written: {dest}")
        written += 1

    eprint("")
    eprint("=== Reconstruction complete ===")
    eprint(f"  Total records : {total}")
    eprint(f"  Written       : {written}")
    eprint(f"  Skipped       : {skipped}")
    eprint(f"  Failed        : {failed}")
    if args.verify_hashes:
        eprint(f"  Hash verified : {verified}")
        eprint(f"  Hash mismatch : {verify_failed}")
        if verify_failed:
            eprint(f"WARNING: {verify_failed} file(s) failed hash verification.")
            return 2
    return 0


# ----------------------------------------------------------------------
# CLI
# ----------------------------------------------------------------------
def build_parser():
    p = argparse.ArgumentParser(
        prog="catdir",
        description="Concatenate files in a directory tree, with ignore patterns "
                    "that can exclude whole directories.",
        epilog="Quote glob patterns (e.g. -i 'results3-*') so your shell does not expand them.",
        allow_abbrev=False,
    )
    p.add_argument("directory", nargs="?", default=None,
                   help="Target directory (default: current directory)")
    p.add_argument("-d", "--directory", dest="directory_opt", metavar="DIR",
                   help="Set target directory")
    p.add_argument("-e", "--ext", default="",
                   help="File extension filter (e.g. 'py', '*.txt')")
    p.add_argument("--maxdepth", type=int, default=None, metavar="N",
                   help="Max recursion depth (default: fully recursive)")
    p.add_argument("-o", "--output", metavar="FILE",
                   help="Write output to a file instead of stdout")
    p.add_argument("-m", "--match", action="store_true",
                   help="Use glob matching for extension (default: strict suffix match)")
    p.add_argument("-or", "--ordered", choices=["alpha", "mtime"], default=None,
                   help="Sort output by 'alpha' or 'mtime'")
    p.add_argument("-s", "--size", type=int, default=None, metavar="BYTES",
                   help="Skip files larger than this many bytes")
    p.add_argument("-i", "--ignore", action="append", default=[], metavar="PATTERN",
                   help="Ignore files or directories matching this pattern "
                        "(repeatable; 'dir/**' excludes a whole directory)")
    p.add_argument("--markdown", action="store_true",
                   help="Wrap file contents in markdown code blocks")
    p.add_argument("-H", "--hash", action="store_true",
                   help="Include the SHA256 hash of each file")
    p.add_argument("-O", dest="output_format", choices=["text", "json"], default="text",
                   help="Output format")
    p.add_argument("-fd", "--fd", action="store_true",
                   help="Use 'fd' for file discovery instead of the built-in walker "
                        "(falls back with a warning if fd is not installed)")
    p.add_argument("-rg", "--rg", action="store_true",
                   help="Use 'rg' (ripgrep) for file discovery instead of the built-in "
                        "walker; takes priority over --fd (falls back if not installed)")
    p.add_argument("-t", "--tree", action="store_true",
                   help="Tree summary mode (file count, total size, tree)")
    p.add_argument("-tx", "--threads", type=int, default=1, metavar="N",
                   help="Threads for size calculation (ONLY with -t/--tree)")
    p.add_argument("--force", action="store_true",
                   help="Allow overwriting existing output file or reconstructed files")
    p.add_argument("--reconstruct", metavar="JSON_FILE",
                   help="Reconstruct a directory tree from a JSON file made with -O json")
    p.add_argument("-rd", "--reconstruct-dir", default=".", metavar="DIR",
                   help="Target directory for reconstruction (default: current directory)")
    p.add_argument("--verify-hashes", action="store_true",
                   help="Verify SHA256 of each reconstructed file")
    return p


def main():
    parser = build_parser()
    args = parser.parse_args()

    if args.reconstruct:
        return run_reconstruct(args)

    args.target = args.directory_opt or args.directory or "."

    if args.output and os.path.exists(args.output) and not args.force:
        eprint(f"Error: output file '{args.output}' already exists. Use --force to overwrite.")
        return 1
    if not os.path.isdir(args.target):
        eprint(f"Error: '{args.target}' is not a valid directory.")
        return 1
    if args.threads < 1:
        eprint("Error: -tx requires a positive integer.")
        return 1
    if args.threads > 1 and not args.tree:
        eprint("Error: Parallel processing (-tx/--threads) can ONLY be used with tree summary mode (-t/--tree).")
        return 1

    ignorer = Ignorer(args.ignore)
    if args.tree:
        return run_tree(args, ignorer)
    return run_concat(args, ignorer)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except BrokenPipeError:
        sys.exit(0)
    except KeyboardInterrupt:
        sys.exit(130)
