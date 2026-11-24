// Which web app this is: the release (from main, at the site's root) or the beta (from develop,
// under beta/). Set at build time (ONIT_CHANNEL, see vite.config.ts). The two share an origin, so
// the user's data and settings are named per channel here: the beta never sees the release's.
// public/sw.js (the share cache) and public/home/ (via home/channel.js) name theirs the same way.
declare const __CHANNEL__: 'release' | 'beta'

export const BETA = __CHANNEL__ === 'beta'

const PREFIX = BETA ? 'onit-beta' : 'onit'

/** A localStorage key: "onit.<name>" in the release, "onit-beta.<name>" in the beta. */
export const storageKey = (name: string) => `${PREFIX}.${name}`

/** An IndexedDB database or a Cache Storage name: "onit-<name>", "onit-beta-<name>" in the beta. */
export const storeName = (name: string) => `${PREFIX}-${name}`

// Shared by both on purpose: what only holds copies of downloaded files that nothing edits.
// Today that's Transformers.js's model cache (Cache Storage "transformers-cache": Whisper's files
// from Hugging Face), kept under its default name in both; neither app deletes from it. The other
// models, the runtime and the sounds come from the site itself (the browser's HTTP cache only).

/** The release's project database, which the beta can copy projects from (read only). */
export const RELEASE_DB = 'onit-projects'
