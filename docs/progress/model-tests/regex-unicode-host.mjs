// Host check of the Unicode property escape that failed in the owner's rea setup on Node 24
// ("Invalid regular expression: /[\p{L}\p{N}]/u"). Node built without full ICU rejects it.
// Run with the Node you care about: node regex-unicode-host.mjs
const probe = [String.raw`[\p{L}\p{N}]`, "u"];
let ok = true;
let error = null;
try {
  new RegExp(probe[0], probe[1]).test("a1");
} catch (e) {
  ok = false;
  error = String(e.message);
}
console.log(JSON.stringify({ node: process.version, icu: process.versions.icu ?? null, unicodeProperty: ok, error }));
