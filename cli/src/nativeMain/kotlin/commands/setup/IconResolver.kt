package commands.setup

import es.jvbabi.kfile.File

/**
 * Resolves a representative favicon or app icon file for a project directory.
 *
 * The resolution happens in two tiers:
 *  1. Look for an image in a format the cloud accepts directly (svg, png, ...):
 *     well-known favicon locations, then `<link rel="icon">` declarations in
 *     common source files, then a recursive search for common icon file names.
 *  2. As a last resort, look for an `.ico` file (same three strategies) and
 *     convert it to PNG via [IcoToPngConverter].
 *
 * The initial candidate list is ported from t3code's ProjectFaviconResolver:
 * https://github.com/pingdotgg/t3code/blob/ecb35f75839925dd1ac6f854efeef5c9e291d11b/apps/server/src/project/ProjectFaviconResolver.ts
 */
object IconResolver {

    /** A resolved icon ready to be uploaded. */
    class ResolvedIcon(val sourcePath: String, val bytes: ByteArray)

    // Well-known favicon paths (directly usable formats) checked in order.
    private val faviconCandidates = listOf(
        "favicon.svg",
        "favicon.png",
        "public/favicon.svg",
        "public/favicon.png",
        "app/favicon.png",
        "app/icon.svg",
        "app/icon.png",
        "src/favicon.svg",
        "src/app/icon.svg",
        "src/app/icon.png",
        "assets/icon.svg",
        "assets/icon.png",
        "assets/logo.svg",
        "assets/logo.png",
        ".idea/icon.svg",
    )

    // Well-known .ico paths, used only for the conversion fallback.
    private val icoCandidates = listOf(
        "favicon.ico",
        "public/favicon.ico",
        "app/favicon.ico",
        "app/icon.ico",
        "src/favicon.ico",
        "src/app/favicon.ico",
    )

    // Files that may contain a <link rel="icon"> or icon metadata declaration.
    private val iconSourceFiles = listOf(
        "index.html",
        "public/index.html",
        "app/routes/__root.tsx",
        "src/routes/__root.tsx",
        "app/root.tsx",
        "src/root.tsx",
        "src/index.html",
    )

    // Matches <link ...> tags where rel/href can appear in any order.
    private val linkIconHtmlRegex = Regex(
        """<link\b(?=[^>]*\brel=["'](?:icon|shortcut icon)["'])(?=[^>]*\bhref=["']([^"'?]+))[^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    // Matches object-like icon metadata (e.g. TanStack Router head links).
    private val linkIconObjectRegex = Regex(
        """(?=[^}]*\brel\s*:\s*["'](?:icon|shortcut icon)["'])(?=[^}]*\bhref\s*:\s*["']([^"'?]+))[^}]*""",
        RegexOption.IGNORE_CASE,
    )

    // Image formats accepted by the cloud (lowercase, without the leading dot).
    private val supportedExtensions = listOf("svg", "png", "jpg", "jpeg", "webp", "gif")

    // File names used for the recursive fallback search of directly usable icons.
    private val fallbackIconNames = listOf("favicon.svg", "app_icon-playstore.png", "logo.svg")

    // Directories skipped during the recursive fallback search.
    private val ignoredDirectories = listOf(
        "node_modules", ".git", ".idea", ".gradle", ".gradle-cache",
        ".gradle-wrapper", "build", "dist", "vendor",
    )

    /**
     * Resolves an icon for [projectRoot], or `null` if none can be found.
     */
    fun resolve(projectRoot: File): ResolvedIcon? {
        val sourceHrefs = collectSourceHrefs(projectRoot)

        // Tier 1: an image format the cloud accepts directly.
        val direct = findFile(projectRoot, faviconCandidates, sourceHrefs, supportedExtensions)
            ?: findImageRecursively(projectRoot, fallbackIconNames)
        if (direct != null) return ResolvedIcon(direct.absolutePath, direct.readBytes())

        // Tier 2: fall back to an .ico file, converted to PNG.
        val ico = findFile(projectRoot, icoCandidates, sourceHrefs, listOf("ico"))
            ?: findImageRecursively(projectRoot, listOf("favicon.ico"))
        if (ico != null) {
            IcoToPngConverter.convert(ico.readBytes())?.let { png ->
                return ResolvedIcon(ico.absolutePath, png)
            }
        }

        return null
    }

    /**
     * Looks for the first existing file matching [allowedExtensions], checking
     * [candidates] first and then paths referenced by [sourceHrefs].
     */
    private fun findFile(
        projectRoot: File,
        candidates: List<String>,
        sourceHrefs: List<String>,
        allowedExtensions: List<String>,
    ): File? {
        candidates.forEach { candidate ->
            findExistingFile(projectRoot, candidate, allowedExtensions)?.let { return it }
        }
        sourceHrefs.forEach { href ->
            resolveIconHref(href).forEach { relativePath ->
                findExistingFile(projectRoot, relativePath, allowedExtensions)?.let { return it }
            }
        }
        return null
    }

    private fun collectSourceHrefs(projectRoot: File): List<String> = iconSourceFiles.mapNotNull { source ->
        val sourceFile = projectRoot.resolve(source)
        if (!sourceFile.exists() || !sourceFile.isFile) return@mapNotNull null
        extractIconHref(sourceFile.readText())
    }

    private fun extractIconHref(source: String): String? {
        linkIconHtmlRegex.find(source)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { return it }
        linkIconObjectRegex.find(source)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    /**
     * Turns an icon href from a source file into candidate relative paths,
     * checking both the raw path and a `public/`-prefixed variant.
     */
    private fun resolveIconHref(href: String): List<String> {
        // Ignore remote or data URLs; only local files can be uploaded.
        if (href.contains("://") || href.startsWith("data:")) return emptyList()
        val clean = href.trimStart('/')
        return listOf("public/$clean", clean)
    }

    /**
     * Resolves [relativePath] against [projectRoot], returning it only if it
     * points to an existing file with an allowed extension that stays within
     * the project directory.
     */
    private fun findExistingFile(projectRoot: File, relativePath: String, allowedExtensions: List<String>): File? {
        // Guard against path traversal escaping the project root.
        if (relativePath.split('/').any { it == ".." }) return null
        val candidate = projectRoot.resolve(relativePath)
        if (!candidate.absolutePath.startsWith(projectRoot.absolutePath)) return null
        if (candidate.extension.lowercase() !in allowedExtensions) return null
        return candidate.takeIf { it.exists() && it.isFile }
    }

    private fun findImageRecursively(directory: File, iconNames: List<String>): File? {
        if (directory.name in ignoredDirectories) return null
        val files = directory.listFiles()
        files.firstOrNull { it.name in iconNames }?.let { return it }
        return files.filter { it.isDirectory }.firstNotNullOfOrNull { findImageRecursively(it, iconNames) }
    }
}
