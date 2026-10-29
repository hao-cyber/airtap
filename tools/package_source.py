#!/usr/bin/env python3
"""Offline, explicit-allowlist source distribution. No build, network or deployment.

Default mode validates and writes a preflight report. --freeze is an explicit final
packaging step after the release documents have been approved.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tempfile
import urllib.parse
import zipfile


ROOT = Path(__file__).resolve().parents[1]
ALLOWLIST = "tools/source-package-files.json"
FIXED_TIME = (1980, 1, 1, 0, 0, 0)
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_TOTAL_BYTES = 24 * 1024 * 1024
BANNED_COMPONENTS = {
    "dist", "node_modules", "build", ".gradle", ".wrangler", ".test-results",
    "__pycache__", ".git", ".venv", "private", ".agents", ".codex", ".aws", ".local", ".ssh",
}
BANNED_SUFFIXES = {".apk", ".aab", ".zip", ".keystore", ".jks", ".p12", ".pfx", ".key", ".pem", ".pyc", ".pyo"}
SENSITIVE_JSON_KEYS = {
    "setup_token", "device_token", "controller_token", "pairing_ticket", "ticket",
    "setup_code", "verification_code", "comparison_code", "claim_id", "authorization",
}
RULES = {
    "real_deployment_hostname": re.compile(r"\b(?:[a-z0-9-]+\.)+(?:memtrix\.dev|trycloudflare\.com|workers\.dev)\b", re.I),
    "maintainer_absolute_path": re.compile(r"/(?:Users/[^/\s]+|home/[^/\s]+|private/tmp|tmp|var/folders)/[^\s\"'<>`),;]+"),
}
SECRET_PATTERNS = {
    "private_key_material": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
    "literal_bearer_credential": re.compile(r"\bBearer [A-Za-z0-9_+/=-]{32,}"),
    "cloudflare_api_token": re.compile(r"\b(?:CLOUDFLARE_API_TOKEN|CF_API_TOKEN)\s*=\s*[\"']?[A-Za-z0-9_-]{30,}"),
}
REPLACEMENTS = {
    "real_deployment_hostname": "redacted-relay.example.invalid",
    "maintainer_absolute_path": "<private-path-omitted>",
}
# This is the public product site, not a relay/deployment wildcard. Only the exact
# hostname and these credential-free HTTPS routes may be preserved. No query,
# userinfo, port, subdomain or hostname suffix is allowed by this exception.
PUBLIC_INSTALL_HOST = "airtap.memtrix.dev"
PUBLIC_INSTALL_PATHS = {"", "/", "/install", "/help", "/privacy", "/release-notes",
                        "/download/android", "/updates/android.json", "/share.png", "/sitemap.xml"}
PUBLIC_HELP_FRAGMENTS = {"", "start", "download", "demo", "ai-help", "connection-help",
                         "install-help", "operation-help", "restricted-settings-help",
                         "pair-help", "offline-help", "remote-help", "release-details"}
GENERATED_PAYLOAD = {"SOURCE-MANIFEST.json", "SHA256SUMS", "SOURCE-PACKAGE.md", "SOURCE-OMISSIONS.md"}
LINK = re.compile(r"!?\[[^\]\n]*\]\((<[^>]+>|[^\s)]+)(?:\s+[\"'][^\n]*[\"'])?\)")


class Refused(ValueError):
    """Messages contain paths/risk types only, never matched source values."""


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def encoded(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()


def public_install_url(value: str) -> bool:
    try:
        parsed = urllib.parse.urlsplit(value)
        return (parsed.scheme == "https" and parsed.netloc == PUBLIC_INSTALL_HOST
                and parsed.path in PUBLIC_INSTALL_PATHS and not parsed.query
                and parsed.fragment in PUBLIC_HELP_FRAGMENTS
                and (not parsed.fragment or parsed.path in {"", "/", "/install", "/help"})
                and "?" not in value)
    except ValueError:
        return False


def public_install_spans(text: str, context: str) -> list[tuple[int, int]]:
    if context == "json_value":
        return [(0, len(text))] if text == PUBLIC_INSTALL_HOST or public_install_url(text) else []
    if context not in {"source", "document"}:
        return []
    spans = []
    # Inspect the complete URL token first, not a hostname prefix inside it.
    for match in re.finditer(r"https?://[^\s<>\"'`\\()\[\]]+", text):
        value = match.group().rstrip(")],;")
        if public_install_url(value):
            spans.append((match.start(), match.start() + len(value)))
    # The Android UI and site builder also use the bare public hostname. The
    # surrounding delimiters cannot be part of a URL, email or longer hostname.
    bare = r"(?<![A-Za-z0-9._~:/@%+\\-])" + re.escape(PUBLIC_INSTALL_HOST) + r"(?![A-Za-z0-9._~:/@%+?#=\\-])"
    spans.extend(match.span() for match in re.finditer(bare, text))
    return spans


def json_deployment_hostname_count(value) -> int:
    pattern = RULES["real_deployment_hostname"]
    if isinstance(value, str):
        return len(rule_matches("real_deployment_hostname", pattern, value, "json_value"))
    if isinstance(value, list):
        return sum(json_deployment_hostname_count(item) for item in value)
    if isinstance(value, dict):
        return sum(len(pattern.findall(key)) + json_deployment_hostname_count(item) for key, item in value.items())
    return 0


def rule_matches(name: str, pattern: re.Pattern, text: str, context: str = "") -> list:
    public_spans = public_install_spans(text, context) if name == "real_deployment_hostname" else []
    return [m for m in pattern.finditer(text)
            if not any(start <= m.start() and m.end() <= end for start, end in public_spans)]


def replace_rule(name: str, pattern: re.Pattern, text: str, context: str) -> tuple[str, int]:
    matches = rule_matches(name, pattern, text, context)
    for match in reversed(matches):
        text = text[:match.start()] + REPLACEMENTS[name] + text[match.end():]
    return text, len(matches)


def safe_path(value: str) -> str:
    if not isinstance(value, str) or not value or "\\" in value or "\x00" in value or ":" in value:
        raise Refused("unsafe_relative_path")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in value.split("/")) or str(path) != value:
        raise Refused("unsafe_relative_path")
    if any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise Refused("unsafe_relative_path")
    for part in path.parts:
        if part.endswith((".", " ")) or re.match(r"(?i)^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)", part):
            raise Refused("unsafe_cross_platform_path")
    return value


def read_regular(root: Path, relative: str) -> bytes:
    safe_path(relative)
    current = root
    for part in PurePosixPath(relative).parts:
        current = current / part
        if current.is_symlink():
            raise Refused(f"symlink_source: {relative}")
    try:
        info = current.stat()
    except FileNotFoundError:
        raise Refused(f"missing_source: {relative}") from None
    if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_FILE_BYTES:
        raise Refused(f"source_type_or_size: {relative}")
    if not current.resolve().is_relative_to(root.resolve()):
        raise Refused(f"outside_source_root: {relative}")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    with os.fdopen(os.open(current, flags), "rb") as stream:
        before = os.fstat(stream.fileno())
        data = stream.read(MAX_FILE_BYTES + 1)
        after = os.fstat(stream.fileno())
    if len(data) > MAX_FILE_BYTES or (before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns):
        raise Refused(f"source_changed_during_read: {relative}")
    return data


def validate_entry(entry: dict) -> None:
    path = safe_path(entry["path"])
    p = PurePosixPath(path)
    if {part.casefold() for part in p.parts} & BANNED_COMPONENTS or p.suffix.lower() in BANNED_SUFFIXES:
        raise Refused(f"excluded_path_in_allowlist: {path}")
    if p.name in {"AGENTS.md", "local.properties", "state.json", "identity.json", "latest.json", "secrets.json", ".env"} or p.name.startswith((".env.", ".dev.vars")):
        raise Refused(f"private_configuration_in_allowlist: {path}")
    if path.startswith("experiments/"):
        raise Refused(f"history_is_intentionally_omitted: {path}")
    kind = entry.get("kind")
    if kind not in {"source", "document", "result", "binary"}:
        raise Refused(f"invalid_entry_kind: {path}")
    if kind == "document" and p.suffix != ".md":
        raise Refused(f"transform_scope: {path}")
    if kind == "result" and (p.suffix != ".json" or "results" not in p.parts):
        raise Refused(f"transform_scope: {path}")
    if path == "docs/agent.md" and kind != "source":
        raise Refused("agent_guide_must_be_byte_identical")
    if kind == "binary" and (not re.fullmatch(r"[0-9a-f]{64}", entry.get("sha256", "")) or not entry.get("review")):
        raise Refused(f"unreviewed_binary: {path}")


def json_sensitive_fields(value, prefix="") -> list[str]:
    found = []
    if isinstance(value, dict):
        for key, item in value.items():
            field = f"{prefix}/{key}"
            if key.lower() in SENSITIVE_JSON_KEYS and isinstance(item, (str, int)) and not isinstance(item, bool) and item not in ("", 0):
                if not (isinstance(item, str) and item.lower() in {"redacted", "[redacted]", "<redacted>", "omitted"}):
                    found.append(field)
            found.extend(json_sensitive_fields(item, field))
    elif isinstance(value, list):
        for index, item in enumerate(value):
            found.extend(json_sensitive_fields(item, f"{prefix}/{index}"))
    return found


def scan(path: str, data: bytes, kind: str) -> list[dict]:
    if kind == "binary":
        return []  # Exact reviewed hashes are required; no claim of binary secret scanning.
    try:
        text = data.decode("utf-8")
    except UnicodeError:
        return [{"path": path, "type": "unreviewed_non_utf8"}]
    findings = []
    for name, pattern in {**RULES, **SECRET_PATTERNS}.items():
        count = len(rule_matches(name, pattern, text, kind))
        if kind == "result" and name == "real_deployment_hostname":
            # JSON syntax is interpreted before considering the exact value;
            # a quote/bracket within a longer string never ends its URL.
            count = json_deployment_hostname_count(json.loads(text))
        if count:
            findings.append({"path": path, "type": name, "count": count})
    if kind == "result" or kind == "source" and path.endswith(".json"):
        for field in json_sensitive_fields(json.loads(text)):
            findings.append({"path": path, "type": "historical_pairing_or_credential_field", "field": field})
    return findings


def redact_document(data: bytes) -> tuple[bytes, list[dict]]:
    text = data.decode("utf-8")
    changes = []
    for rule, pattern in RULES.items():
        text, count = replace_rule(rule, pattern, text, "document")
        if count:
            changes.append({"rule": rule, "count": count})
    return text.encode(), changes


def redact_result(data: bytes) -> tuple[bytes, list[dict]]:
    value = json.loads(data)
    counts = {rule: 0 for rule in RULES}

    def visit(obj):
        if isinstance(obj, str):
            for rule, pattern in RULES.items():
                obj, count = replace_rule(rule, pattern, obj, "json_value")
                counts[rule] += count
            return obj
        if isinstance(obj, list):
            return [visit(item) for item in obj]
        if isinstance(obj, dict):
            return {key: visit(item) for key, item in obj.items()}
        return obj

    value = visit(value)
    changes = [{"rule": rule, "count": count} for rule, count in counts.items() if count]
    if not changes:
        return data, []
    if not isinstance(value, dict):
        raise Refused("unsupported_result_shape")
    value["_source_distribution"] = {
        "status": "redacted_distribution_copy",
        "original_sha256": digest(data),
        "notice": "Only deployment addresses/private paths were replaced. Historical source_snapshots hashes refer to the original audited artifacts, not these redacted bytes. See SOURCE-MANIFEST.json.",
    }
    return encoded(value), changes


def without_fenced_code(text: str) -> str:
    # Preserve offsets so link rewrites affect only visible Markdown, never code.
    lines = text.splitlines(keepends=True)
    fence = None
    out = []
    for line in lines:
        match = re.match(r"^\s*(`{3,}|~{3,})", line)
        if match:
            marker = match.group(1)
            if fence is None:
                fence = marker[0]
            elif marker[0] == fence:
                fence = None
            out.append("".join("\n" if c == "\n" else " " for c in line))
        elif fence:
            out.append("".join("\n" if c == "\n" else " " for c in line))
        else:
            out.append(line)
    return "".join(out)


def local_links(path: str, data: bytes):
    text = data.decode()
    for match in LINK.finditer(without_fenced_code(text)):
        raw = match.group(1).strip("<>")
        parsed = urllib.parse.urlsplit(raw)
        if parsed.scheme or parsed.netloc:
            continue
        if not parsed.path:
            if parsed.fragment:
                yield match, path, urllib.parse.unquote(parsed.fragment)
            continue
        decoded = urllib.parse.unquote(parsed.path)
        if decoded.startswith("/") or "\\" in decoded:
            raise Refused(f"absolute_markdown_link: {path}")
        parts = list(PurePosixPath(path).parent.parts)
        for part in decoded.split("/"):
            if part == "..":
                if not parts:
                    raise Refused(f"escaping_markdown_link: {path}")
                parts.pop()
            elif part not in {"", "."}:
                parts.append(part)
        yield match, safe_path("/".join(parts)), urllib.parse.unquote(parsed.fragment)


def omittable(path: str) -> bool:
    return (path.startswith("experiments/") or path.startswith("docs/results/pairing-")
            or path in {"docs/results/native-readonly-attempt-v2.json", "docs/results/replication-entry-discovery-2026-10-16.json"})


def omission_reason(path: str) -> str:
    return "historical_experiment_not_required_for_current_reproduction" if path.startswith("experiments/") else "raw_pairing_or_maintenance_record_not_distributed"


def relative_link(source: str, target: str) -> str:
    return os.path.relpath(target, PurePosixPath(source).parent).replace(os.sep, "/")


def build_payload(root: Path, config: dict) -> tuple[dict[str, bytes], dict, list[dict]]:
    from source_manifest import validate_closure
    closure = validate_closure(root, config)
    paths = {}
    rows = {}
    seen = set()
    findings = []
    entries = config.get("files", [])
    for entry in entries:
        validate_entry(entry)
        name, kind = entry["path"], entry["kind"]
        if name in GENERATED_PAYLOAD:
            raise Refused("generated_file_in_allowlist: " + name)
        if name.casefold() in seen:
            raise Refused(f"duplicate_or_casefold_path: {name}")
        seen.add(name.casefold())
        original = read_regular(root, name)
        if kind == "binary" and digest(original) != entry["sha256"]:
            raise Refused(f"reviewed_binary_hash_changed: {name}")
        data, changes = original, []
        if kind == "document":
            data, changes = redact_document(original)
        elif kind == "result":
            data, changes = redact_result(original)
        findings.extend(scan(name, data, kind))
        paths[name] = data
        rows[name] = {"path": name, "kind": kind, "original_sha256": digest(original),
                      "original_bytes": len(original), "status": "redacted_copy" if changes else "byte_identical",
                      "transformations": changes, "mode": "0755" if name == "apps/android/gradlew" else "0644"}
        if kind == "binary":
            rows[name]["binary_review"] = entry["review"]
    if findings:
        raise Refused("distribution_scan_findings: " + json.dumps(findings, ensure_ascii=False, sort_keys=True))

    omissions = {}
    for name in list(paths):
        if not name.endswith(".md"):
            continue
        replacements = []
        for match, target, _fragment in local_links(name, paths[name]):
            if target in paths or target in GENERATED_PAYLOAD or any(p.startswith(target + "/") for p in paths):
                continue
            if not omittable(target):
                raise Refused(f"unlisted_markdown_target: {name} -> {target}")
            original = read_regular(root, target)
            reason = omission_reason(target)
            omissions[target] = {"path": target, "reason": reason, "original_sha256": digest(original), "original_bytes": len(original)}
            if target.endswith(".json"):
                paths[target] = encoded({"schema": "airtap-source-omission-v1", "status": "original_record_not_included",
                    "path": target, "reason": reason, "original_sha256": digest(original),
                    "notice": "This is an omission notice, not the original result. No original response, pairing data or raw log is included. Historical conclusions remain in the accompanying Markdown reports."})
                rows[target] = {"path": target, "kind": "omission", "status": "original_omitted",
                    "original_sha256": digest(original), "original_bytes": len(original), "mode": "0644",
                    "transformations": [{"rule": "replace_raw_record_with_explicit_omission", "count": 1}]}
            else:
                if rows[name]["kind"] != "document":
                    raise Refused(f"immutable_markdown_has_omitted_link: {name}")
                anchor = "omitted-" + digest(target.encode())[:12]
                replacement = relative_link(name, "SOURCE-OMISSIONS.md") + "#" + anchor
                replacements.append((match.start(1), match.end(1), replacement))
        if replacements:
            text = paths[name].decode()
            for start, end, replacement in reversed(replacements):
                text = text[:start] + replacement + text[end:]
            paths[name] = text.encode()
            rows[name]["status"] = "distribution_document_copy"
            rows[name]["transformations"].append({"rule": "redirect_omitted_history_links", "count": len(replacements)})

    omission_text = "# Historical material omitted from this source distribution\n\nThis is a curated current implementation, not a complete Git repository or private evidence archive. Current Android, CLI and relay code/tests are included. Historical experiments and raw pairing/maintenance records are intentionally excluded. The original audit hashes below identify the originals; they do not assert that original bytes are in this package.\n"
    for path, info in sorted(omissions.items()):
        omission_text += f"\n## omitted-{digest(path.encode())[:12]}\n\n`{path}`\n\nReason: `{info['reason']}`. Original SHA256: `{info['original_sha256']}`.\n"
    paths["SOURCE-OMISSIONS.md"] = omission_text.encode()
    paths["SOURCE-PACKAGE.md"] = package_notice(config)
    # Package-local notice is intentionally separate from immutable source/agent instructions.
    readme_note = "> 源码分发副本：请先阅读 [源码包范围、构建和校验说明](SOURCE-PACKAGE.md)。历史记录可能为脱敏副本或明确 omission；详见 SOURCE-MANIFEST.json。\n\n"
    paths["README.md"] = readme_note.encode() + paths["README.md"]
    rows["README.md"]["status"] = "distribution_document_copy"
    rows["README.md"]["transformations"].append({"rule": "prepend_source_distribution_notice", "count": 1})

    for name in ["SOURCE-PACKAGE.md", "SOURCE-OMISSIONS.md"]:
        rows[name] = {"path": name, "kind": "generated_document", "status": "generated", "original_sha256": None,
                      "original_bytes": None, "mode": "0644", "transformations": []}
    for name, data in paths.items():
        rows[name]["sha256"] = digest(data)
        rows[name]["bytes"] = len(data)
        findings.extend(scan(name, data, "binary" if rows[name]["kind"] == "binary" else
                             "result" if rows[name]["kind"] in {"result", "omission"} else
                             "document" if rows[name]["kind"] in {"document", "generated_document"} else "source"))
    if findings:
        raise Refused("final_distribution_scan_findings: " + json.dumps(findings, ensure_ascii=False, sort_keys=True))
    for row in rows.values():
        if row["kind"] == "source" and row["original_sha256"] != row["sha256"]:
            raise Refused(f"immutable_source_changed: {row['path']}")
    if sum(len(v) for v in paths.values()) > MAX_TOTAL_BYTES:
        raise Refused("distribution_size_limit")
    # Detect edits that happened after an earlier file was read; the release
    # workflow must provide a stable checkout, not silently mix source revisions.
    for name, row in rows.items():
        if row["kind"] in {"source", "binary"} and digest(read_regular(root, name)) != row["original_sha256"]:
            raise Refused("source_changed_during_packaging: " + name)
    validate_closure(root, config)
    links = verify_links(paths)
    manifest = {"schema": "airtap-source-manifest-v1", "version": config["version"], "archive_root": config["archive_root"],
        "scope": config["scope"], "source_files_are_byte_identical": all(row["original_sha256"] == row["sha256"] for row in rows.values() if row["kind"] == "source"),
        "historical_hash_notice": "Historical source_snapshots hashes are original audit values; use each entry.sha256 for the distributed bytes.",
        "manifest_coverage": "All payload files except this manifest and SHA256SUMS; SHA256SUMS covers this manifest as well.",
        "entries": [rows[p] for p in sorted(rows)], "omitted_originals": [omissions[p] for p in sorted(omissions)],
        "verification": {"source_closure": closure, "markdown_local_links": links, "binary_policy": "Exact pinned hashes with prior review; no blanket binary secret scan.",
                         "secret_scan": "Selected UTF-8 files: known deployment-host/private-path patterns, private-key/bearer literals and credential fields in selected JSON. Heuristic, not a comprehensive security guarantee."}}
    paths["SOURCE-MANIFEST.json"] = encoded(manifest)
    paths["SHA256SUMS"] = "".join(f"{digest(paths[p])}  {p}\n" for p in sorted(paths)).encode()
    return paths, manifest, findings


def package_notice(config: dict) -> bytes:
    allowlist = config.get("allowlist_path", ALLOWLIST)
    service_preview = config.get("protocol_version") in (3, 4)
    relay_scope = "多设备自助登记 Workers 中继" if service_preview else "单手机 Workers 中继"
    delivery_scope = (
        "普通体验者使用发布方预配置服务地址的 App，经手机登记、权限准备、短期 prompt 和核码批准接入；当前版本不使用长 setup 登记码。公共服务与新版真机接入仍须按当前证据确认，本源码包不提供线上服务或已完成公众开放的保证。"
        if service_preview else
        "普通体验者仍需维护者提供独立登记信息；本包没有可直接使用的公共中继凭据。"
    )
    package_command = "python3 tools/package_source.py" + (" --allowlist " + allowlist if allowlist != ALLOWLIST else "")
    return f"""# Airtap {config['version']} source distribution

这是当前 Android `{config['android_version']}`（versionCode `{config['version_code']}`）、CLI 和{relay_scope}的源码分发包，不是完整 Git 仓库归档，也不是正式发布版。历史 `experiments/` 执行目录、原始测试日志、私人部署状态、开发签名密钥、APK 和依赖缓存刻意不包含；源码包本身不会连接中继、注册设备或部署服务。

从 [完整复刻指南](docs/reproduce.md) 开始。Android 需要 JDK 17+ 和 Android SDK；Gradle wrapper/固定校验值已包含。首次构建及 `npm ci` 仍需下载公开依赖，这不是离线依赖全集。当前产品源码、生产资源和测试脚本与打包时的源文件逐字一致，所声明的 Android、CLI、中继、发布工具、CI 与测试目录已检查文件闭包；新增目录仍需显式纳入闭包策略。`docs/agent.md` 也保持原样。运行测试请按各目录说明；测试中保存的历史摘要不是本次重新验收。部分历史 Android 诊断脚本绑定当时的版本与摘要，不能将全部历史脚本视为适用于最新源码的一套测试；某些脚本会写入结果目录，宜在自己的工作副本中运行并区分新结果与附带的历史记录。

默认 debug 构建在自己的电脑上生成或使用自己的 Android debug key。**重构建 APK 的摘要和签名通常与维护者 APK 不同**，本包不提供维护者私钥，也不能保证覆盖安装维护者签名的 APK。保留自己的签名材料用于后续升级；正式签名与发行另行管理。本源码 ZIP 的确定性不等于 Android APK 可逐字重构建。

`SOURCE-MANIFEST.json` 逐项记录原文件 SHA256、分发文件 SHA256、范围和变换规则。仅文档/历史摘要允许定点替换真实部署域名与维护者路径；被排除的原始记录使用明确 omission 存根或 [遗漏说明](SOURCE-OMISSIONS.md)。历史结果中的 `source_snapshots` 等 SHA256 **仍是原始审计值，不能用于声称脱敏副本与原记录逐字一致**。事实数值、失败和验收边界没有借分发而改写。

ZIP 内只有安全相对路径及普通文件，采用固定时间、排序、权限和无压缩 ZIP 编码。`SHA256SUMS` 校验所有其他包内文件（含 manifest）；manifest 不自哈希。维护者流程见[源码导出说明](docs/source-distribution.md)。根目录 `.gitignore` 不是打包依据：打包器只读取 `{allowlist}` 中的明确白名单，拒绝符号链接、未列入的依赖链接和未审查二进制。只保留构建所需且已审查固定摘要的二进制；原始手机截图、聊天和维护证据不随当前源码包分发。公开产品站点仅精确允许其 HTTPS 地址和无凭据公开路径，其他部署主机仍会拒绝或按文档规则脱敏。

解压后先进入含有 `apps/`、`cli/` 和 `relay/` 的源码根目录，用 `shasum -a 256 -c SHA256SUMS` 校验包内文件；Linux 如无 `shasum`，可用 `sha256sum -c SHA256SUMS`。下载 ZIP 的校验值另取对应 Release 的交付清单。摘要核对不能替代对分发来源的信任。

源码包使用者不需要运行打包器。`{package_command}` 仅供维护者在保有原始完整文件与历史证据的发行工作区预检；最终版本与文档确认后才运行 `{package_command} --freeze`。已分发目录含生成说明和脱敏／omission 内容，重新打包可能因生成文件或缺失原件被拒绝；本包不是原始发布工作区的替代。源码构建、CLI 和部署不依赖重新生成此分发包。

{delivery_scope}当前验收结论以 [README](README.md) 及相应版本的证据为准，不能把某一版本、计算器或维护者测试的成功推广到任意 AI、浏览器、机型或独立新用户。
""".encode()


def markdown_anchors(data: bytes) -> set[str]:
    text = without_fenced_code(data.decode())
    anchors = set(re.findall(r'<[^>]+\b(?:id|name)=["\']([^"\']+)["\']', text))
    for line in text.splitlines():
        heading = re.match(r"^ {0,3}#{1,6}\s+(.+?)(?:\s+#+)?\s*$", line)
        if not heading:
            continue
        title = re.sub(r"!?\[([^]]+)\]\([^)]+\)", r"\1", heading.group(1))
        title = html.unescape(re.sub(r"<[^>]+>", "", title)).replace("`", "").replace("*", "")
        slug = re.sub(r"[^\w\- ]", "", title.lower()).replace(" ", "-")
        candidate, number = slug, 0
        while candidate in anchors:
            number += 1
            candidate = f"{slug}-{number}"
        anchors.add(candidate)
    return anchors


def verify_links(paths: dict[str, bytes]) -> int:
    count = 0
    for name, data in paths.items():
        if not name.endswith(".md"):
            continue
        for _match, target, fragment in local_links(name, data):
            if target not in paths and not any(p.startswith(target + "/") for p in paths):
                raise Refused(f"missing_distributed_link: {name} -> {target}")
            if fragment and target.endswith(".md"):
                if fragment not in markdown_anchors(paths[target]):
                    raise Refused(f"missing_markdown_anchor: {name} -> {target}")
            count += 1
    return count


def zip_bytes(paths: dict[str, bytes], archive_root: str) -> bytes:
    safe_path(archive_root)
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_STORED) as archive:
        for path, data in sorted(paths.items()):
            safe_path(path)
            entry = zipfile.ZipInfo(archive_root + "/" + path, FIXED_TIME)
            entry.create_system = 3
            entry.compress_type = zipfile.ZIP_STORED
            mode = 0o755 if path == "apps/android/gradlew" else 0o644
            entry.external_attr = (stat.S_IFREG | mode) << 16
            archive.writestr(entry, data)
    return stream.getvalue()


def verify_archive(data: bytes, paths: dict[str, bytes], archive_root: str) -> dict:
    safe_path(archive_root)
    seen = set()
    total = 0
    with zipfile.ZipFile(io.BytesIO(data)) as archive, tempfile.TemporaryDirectory(prefix="airtap-source-check-") as tmp:
        base = Path(tmp).resolve()
        for entry in archive.infolist():
            name = safe_path(entry.filename)
            if name.casefold() in seen or not name.startswith(archive_root + "/"):
                raise Refused("duplicate_or_outside_archive_path")
            seen.add(name.casefold())
            mode = entry.external_attr >> 16
            if not stat.S_ISREG(mode) or entry.is_dir() or entry.flag_bits & 1 or entry.date_time != FIXED_TIME:
                raise Refused("unsafe_archive_metadata")
            relative = name[len(archive_root) + 1:]
            expected_mode = 0o755 if relative == "apps/android/gradlew" else 0o644
            if stat.S_IMODE(mode) != expected_mode:
                raise Refused("unexpected_archive_permissions")
            if relative not in paths or entry.file_size != len(paths[relative]):
                raise Refused("unexpected_archive_member")
            total += entry.file_size
            if total > MAX_TOTAL_BYTES:
                raise Refused("archive_size_limit")
            content = archive.read(entry)
            if content != paths[relative]:
                raise Refused("archive_content_mismatch")
            destination = base / name
            if not destination.resolve().is_relative_to(base):
                raise Refused("archive_path_escape")
            destination.parent.mkdir(parents=True, exist_ok=True)
            with destination.open("xb") as stream:
                stream.write(content)
            destination.chmod(expected_mode)
            if digest(destination.read_bytes()) != digest(paths[relative]):
                raise Refused("extracted_content_mismatch")
            if stat.S_IMODE(destination.stat().st_mode) != expected_mode:
                raise Refused("extracted_mode_mismatch")
        if len(seen) != len(paths):
            raise Refused("archive_member_missing")
    return {"members": len(seen), "extracted_bytes": total, "all_regular_relative_unique": True,
            "safe_extraction_and_hashes": True, "exact_zip_and_extracted_permissions": True, "files_executed": False}


def write_new_or_identical(path: Path, data: bytes) -> None:
    if path.is_symlink():
        raise Refused("symlink_output")
    if path.exists():
        if path.read_bytes() == data:
            return
        raise Refused(f"different_frozen_output_exists: {path.name}")
    with path.open("xb") as stream:
        stream.write(data)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--freeze", action="store_true", help="create final ZIP and fixed manifests after documents are approved")
    parser.add_argument("--output", type=Path, default=ROOT / "dist")
    parser.add_argument("--allowlist", default=ALLOWLIST,
                        help="reviewed tools/source-package-files[-vNNN].json; same privacy and path checks apply")
    args = parser.parse_args(argv)
    safe_path(args.allowlist)
    if not re.fullmatch(r"tools/source-package-files(?:-v[0-9]{3,})?\.json", args.allowlist):
        raise Refused("unsupported_allowlist_path")
    allowlist_bytes = read_regular(ROOT, args.allowlist)
    config = json.loads(allowlist_bytes)
    if config.get("allowlist_path", ALLOWLIST) != args.allowlist:
        raise Refused("allowlist_identity_mismatch")
    if config.get("schema") != "airtap-source-allowlist-v1":
        raise Refused("invalid_allowlist")
    paths, manifest, findings = build_payload(ROOT, config)
    gradle = paths["apps/android/app/build.gradle"].decode()
    version_name = re.search(r'\bversionName\s+["\']([^"\']+)', gradle)
    version_code = re.search(r"\bversionCode\s+(\d+)", gradle)
    if not version_name or not version_code or version_name.group(1) != config["android_version"] or int(version_code.group(1)) != config["version_code"]:
        raise Refused("android_version_allowlist_mismatch")
    cli_versions = re.findall(r'^VERSION\s*=\s*["\']([^"\']+)["\']\s*$', paths["cli/airtap.py"].decode(), re.M)
    cli_version = cli_versions[0] if len(cli_versions) == 1 else None
    if "cli_version" in config and (not isinstance(config["cli_version"], str) or cli_version != config["cli_version"]):
        raise Refused("cli_version_allowlist_mismatch")
    report = {"schema": "airtap-source-verification-v1", "status": "preflight_passed_not_frozen",
              "version": config["version"], "allowlist_path": args.allowlist,
              "allowlist_sha256": digest(allowlist_bytes), "files": len(paths), "originals_omitted": len(manifest["omitted_originals"]),
              "source_files_unchanged": all(x["original_sha256"] == x["sha256"] for x in manifest["entries"] if x["kind"] == "source"),
              "markdown_local_links": manifest["verification"]["markdown_local_links"], "source_closure": manifest["verification"]["source_closure"], "scan_findings": findings,
              "binary_checks": "Pinned reviewed hashes; no claim of general binary secret scanning.",
              "scope": config["scope"], "network_build_deploy_phone_actions": False,
              "android_version": version_name.group(1), "android_version_code": int(version_code.group(1)),
              "cli_version": cli_version,
              "cli_sha256": digest(paths["cli/airtap.py"]), "agent_guide_sha256": digest(paths["docs/agent.md"]),
              "scan_limit": manifest["verification"]["secret_scan"]}
    archive = zip_bytes(paths, config["archive_root"])
    if archive != zip_bytes(paths, config["archive_root"]):
        raise Refused("nondeterministic_archive")
    report.update(verify_archive(archive, paths, config["archive_root"]))
    report["deterministic_second_encoding_identical"] = True
    args.output.mkdir(parents=True, exist_ok=True)
    stem = config["archive_root"]
    if args.freeze:
        report.update(status="frozen_and_verified", zip_sha256=digest(archive), zip_bytes=len(archive),
                      manifest_sha256=digest(paths["SOURCE-MANIFEST.json"]))
        outputs = {stem + ".zip": archive, stem + ".manifest.json": paths["SOURCE-MANIFEST.json"],
                   stem + ".SHA256SUMS": paths["SHA256SUMS"], stem + ".zip.sha256": f"{digest(archive)}  {stem}.zip\n".encode(),
                   stem + ".verification.json": encoded(report)}
        # Preflight every destination before writing any output.
        for name, data in outputs.items():
            target = args.output / name
            if target.is_symlink() or target.exists() and target.read_bytes() != data:
                raise Refused(f"different_frozen_output_exists: {name}")
        for name, data in outputs.items():
            write_new_or_identical(args.output / name, data)
    else:
        target = args.output / (stem + ".preflight.json")
        if target.is_symlink():
            raise Refused("symlink_output")
        target.write_bytes(encoded(report))
    print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    # Closure helpers import this module; share the controlled Refused type in CLI mode.
    sys.modules["package_source"] = sys.modules[__name__]
    try:
        raise SystemExit(main())
    except (Refused, OSError, ValueError) as failure:
        # OSError/JSON messages may include input excerpts; only our controlled messages are emitted.
        print(str(failure) if isinstance(failure, Refused) else "packaging_failed_io_or_format")
        raise SystemExit(1)
