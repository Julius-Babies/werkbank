import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
	return twMerge(clsx(inputs));
}

type DecompressionFormat = "gzip" | "deflate";

/**
 * Undoes the compression of a blob if it is compressed, otherwise returns it
 * unchanged. Only the formats the browser's DecompressionStream supports
 * (gzip, deflate) are handled — brotli and zstd bodies are passed through
 * untouched. If decompression fails the original blob is returned.
 *
 * Pass the `Content-Encoding` header value as `contentEncoding` to have that
 * decide the format (this is authoritative): a supported value decompresses,
 * `null`/`identity`/an unsupported value passes through. Omit the argument to
 * instead sniff the format from the blob's magic bytes.
 */
export async function decompressBlob(blob: Blob, contentEncoding?: string | null): Promise<Blob> {
	const format =
		contentEncoding === undefined
			? await sniffCompressionFormat(blob)
			: contentEncodingToFormat(contentEncoding);

	if (format === null) return blob;

	try {
		const stream = blob.stream().pipeThrough(new DecompressionStream(format));
		return await new Response(stream).blob();
	} catch {
		return blob;
	}
}

/** Maps a `Content-Encoding` header value to a supported format, or null. */
function contentEncodingToFormat(contentEncoding: string | null): DecompressionFormat | null {
	// A chain like "deflate, gzip" is applied left-to-right, so the last token
	// is the outermost (and only) layer we can peel off.
	const encoding = contentEncoding?.split(",").pop()?.trim().toLowerCase();
	switch (encoding) {
		case "gzip":
		case "x-gzip":
			return "gzip";
		case "deflate":
			return "deflate";
		default:
			return null;
	}
}

/** Detects gzip/deflate from a blob's magic bytes, or null if neither. */
async function sniffCompressionFormat(blob: Blob): Promise<DecompressionFormat | null> {
	const header = new Uint8Array(await blob.slice(0, 2).arrayBuffer());
	if (header.length < 2) return null;
	// gzip magic number: 0x1f 0x8b
	if (header[0] === 0x1f && header[1] === 0x8b) return "gzip";
	// zlib/deflate: 0x78 CMF byte with a valid header checksum
	if (header[0] === 0x78 && (header[0] * 256 + header[1]) % 31 === 0) return "deflate";
	return null;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type WithoutChild<T> = T extends { child?: any } ? Omit<T, "child"> : T;
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type WithoutChildren<T> = T extends { children?: any } ? Omit<T, "children"> : T;
export type WithoutChildrenOrChild<T> = WithoutChildren<WithoutChild<T>>;
export type WithElementRef<T, U extends HTMLElement = HTMLElement> = T & { ref?: U | null };
