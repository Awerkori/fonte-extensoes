from urllib.parse import unquote, urlsplit

import index_pb2


def update_legacy_index(existing, published, removed_modules):
    """Apply the same explicit publication batch to the historical v1 catalog."""
    previous = {entry["pkg"]: entry for entry in existing}
    removed_set = set(removed_modules)
    entries = {
        package: entry
        for package, entry in previous.items()
        if package not in removed_set and not any(package.endswith(f".{module}") for module in removed_set)
    }
    for extension in published:
        package = extension.packageName
        old = previous.get(package, {})
        old_sources = {str(source["id"]): source for source in old.get("sources", [])}
        sources = [
            {
                **old_sources.get(str(source.id), {}),
                "name": source.name,
                "lang": source.language,
                "id": str(source.id),
                "baseUrl": source.homeUrl,
            }
            for source in extension.sources
        ]
        # Inverse of ExtensionPlugin.androidVersionCodeProvider, including theme base.
        library_code = int("".join(part.zfill(2) for part in extension.extensionLib.split("."))) * 1000
        code = extension.versionCode - library_code
        if code < 0 or extension.versionName != f"{extension.extensionLib}.{code}":
            raise ValueError(f"Inconsistent legacy version metadata for {package}")
        entries[package] = {
            **old,
            "name": extension.name,
            "pkg": package,
            "apk": unquote(urlsplit(extension.resources.apkUrl).path.rsplit("/", 1)[-1]),
            "lang": sources[0]["lang"] if sources else "all",
            "code": code,
            "version": extension.versionName,
            "nsfw": int(extension.contentWarning == index_pb2.CONTENT_WARNING_NSFW),
            "sources": sources,
        }
    result = [entries[package] for package in sorted(entries)]
    if len(result) != len({e["pkg"] for e in result}):
        raise ValueError("Duplicate packages detected in legacy index")
    return result
