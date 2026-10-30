// Static check of the skill header rule taken from upstream OpenCode at 05ea5073:
// packages/opencode/src/skill/index.ts - a SKILL.md is loaded only when its
// frontmatter is a record with a string `name`, and `description` is optional
// but must be a string when present. Anything else is dropped without an error.
// Usage: node check-skill-frontmatter.mjs <dir-with-SKILL.md-folders>
import fs from "fs";
import path from "path";

const root = process.argv[2] || ".";
const results = [];
for (const dir of fs.readdirSync(root)) {
  const file = path.join(root, dir, "SKILL.md");
  if (!fs.existsSync(file)) continue;
  const text = fs.readFileSync(file, "utf8");
  const m = text.match(/^---\n([\s\S]*?)\n---\n/);
  if (!m) { results.push({ folder: dir, loaded: false, reason: "no frontmatter" }); continue; }
  const fields = {};
  for (const line of m[1].split("\n")) {
    const kv = line.match(/^([A-Za-z_]+):\s*(.*)$/);
    if (kv) fields[kv[1]] = kv[2].trim();
  }
  const hasName = typeof fields.name === "string" && fields.name.length > 0;
  results.push({
    folder: dir,
    loaded: hasName,
    skillName: hasName ? fields.name : null,
    reason: hasName ? "name present" : "no name: upstream drops it silently",
  });
}
console.log(JSON.stringify(results, null, 2));
