// `node scripts/docs.mjs prepare` runs before `next build` and `next dev`: it checks that every
// snippet and excerpt the pages name exists, copies the logo into public/, and writes the
// changelog page.
// `node scripts/docs.mjs check` runs after the build: it puts each page's Markdown next to the
// page (`/docs/x/` has `/docs/x.md`) and fails on internal links that lead nowhere.
import fs from 'node:fs';
import path from 'node:path';
import { DOCS, ROOT, REPOSITORY, DocsError, resolveFence, snippets } from './sources.mjs';

const CONTENT = path.join(DOCS, 'content/docs');
const PUBLIC = path.join(DOCS, 'public');
const OUT = path.join(DOCS, 'out');

function walk(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name);
    return e.isDirectory() ? walk(p) : [p];
  });
}

function copy(from, to) {
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
}

// --- prepare ---

function changelog() {
  const dir = path.join(ROOT, 'changelog/unreleased');
  const files = (fs.existsSync(dir) ? fs.readdirSync(dir) : []).filter((f) => f.endsWith('.md')).sort().reverse();
  const byDate = new Map();
  for (const f of files) {
    const date = f.slice(0, 10);
    if (!byDate.has(date)) byDate.set(date, []);
    byDate.get(date).push(fs.readFileSync(path.join(dir, f), 'utf8').trim());
  }
  let text = '---\ntitle: Changelog\ndescription: What changed since the last release, newest first.\n---\n\n'
    + 'Nothing is released yet: these are the changes on the way to the first release, newest first. '
    + `Each comes from a file under [\`changelog/unreleased\`](${REPOSITORY}/tree/main/changelog/unreleased) `
    + 'in the repository, which the release folds into `CHANGELOG.md`.\n';
  if (!byDate.size) text += '\nNo changes are recorded yet.\n';
  for (const [date, entries] of byDate) text += `\n## ${date}\n\n${entries.join('\n')}\n`;
  fs.writeFileSync(path.join(CONTENT, 'reference/changelog.md'), text);
}

function prepare() {
  const problems = [];
  snippets();
  const pages = [...walk(CONTENT), ...walk(path.join(DOCS, 'app'))]
    .filter((f) => f.endsWith('.mdx') || f.endsWith('.tsx'));
  for (const file of pages) {
    const rel = path.relative(DOCS, file);
    const lines = fs.readFileSync(file, 'utf8').split('\n');
    lines.forEach((line, i) => {
      const where = `${rel}:${i + 1}`;
      try {
        if (/^(title|description): [^"'].*: /.test(line)) {
          throw new DocsError(`${where}: quote the ${line.split(':')[0]}, which has ": " in it`);
        }
        const fence = line.match(/^\s*```(\w*)\s*(.*)$/);
        if (fence && fence[1] && file.endsWith('.mdx')) {
          const resolved = resolveFence(fence[2], where);
          if (!resolved && fence[1] === 'java') {
            throw new DocsError(`${where}: Java code comes from a snippet: write \`\`\`java snippet="name"\`\`\` and mark the code in examples' docs package`);
          }
        }
      } catch (e) {
        if (!(e instanceof DocsError)) throw e;
        problems.push(e.message);
      }
    });
  }
  if (problems.length) {
    console.error(`docs: ${problems.length} problem(s):\n  ${problems.join('\n  ')}`);
    process.exit(1);
  }
  copy(path.join(ROOT, 'assets/logo.svg'), path.join(PUBLIC, 'logo.svg'));
  changelog();
  console.log(`docs: ${snippets().size} snippets`);
}

// --- check ---

/** The file under out/ that serves a site path, or null. */
function served(urlPath) {
  const p = decodeURIComponent(urlPath);
  const candidates = p.endsWith('/')
    ? [path.join(OUT, p, 'index.html')]
    : [path.join(OUT, p), path.join(OUT, p, 'index.html'), path.join(OUT, p + '.html')];
  return candidates.find((c) => fs.existsSync(c) && fs.statSync(c).isFile()) ?? null;
}

function check() {
  // Each page's Markdown beside the page: /docs/concepts/sources/ has /docs/concepts/sources.md.
  const markdown = path.join(OUT, 'llms.mdx/docs');
  let pages = 0;
  for (const file of walk(markdown).filter((f) => f.endsWith('content.md'))) {
    const slug = path.relative(markdown, path.dirname(file));
    copy(file, path.join(OUT, 'docs', slug ? `${slug}.md` : 'index.md'));
    pages++;
  }

  const ids = new Map();
  const idsOf = (file) => {
    if (!ids.has(file)) {
      ids.set(file, new Set([...fs.readFileSync(file, 'utf8').matchAll(/\sid="([^"]+)"/g)].map((m) => m[1])));
    }
    return ids.get(file);
  };
  const dead = [];
  const html = walk(OUT).filter((f) => f.endsWith('.html'));
  for (const file of html) {
    const text = fs.readFileSync(file, 'utf8');
    const page = '/' + path.relative(OUT, file).replace(/index\.html$/, '').replace(/\.html$/, '');
    for (const m of text.matchAll(/\s(?:href|src)="([^"]*)"/g)) {
      const link = m[1].replaceAll('&amp;', '&');
      if (!link.startsWith('/') && !link.startsWith('#')) continue;
      if (link.startsWith('//') || link.startsWith('/_next/')) continue;
      const [target, hash] = link.split('#');
      const pathOnly = target.split('?')[0];
      const servedFile = pathOnly ? served(pathOnly) : file;
      if (!servedFile) {
        dead.push(`${page}: ${link}`);
        continue;
      }
      if (hash && servedFile.endsWith('.html') && !idsOf(servedFile).has(decodeURIComponent(hash))) {
        dead.push(`${page}: ${link} (no #${hash} there)`);
      }
    }
  }
  for (const txt of ['llms.txt', 'llms-full.txt']) {
    const text = fs.readFileSync(path.join(OUT, txt), 'utf8');
    for (const m of text.matchAll(/\]\((\/[^)\s#]*)/g)) {
      if (!served(m[1])) dead.push(`/${txt}: ${m[1]}`);
    }
  }
  if (dead.length) {
    console.error(`docs: ${dead.length} internal link(s) lead nowhere:\n  ${[...new Set(dead)].join('\n  ')}`);
    process.exit(1);
  }
  console.log(`docs: ${pages} pages as Markdown, links of ${html.length} HTML files checked`);
}

const command = process.argv[2];
try {
  if (command === 'prepare') prepare();
  else if (command === 'check') check();
  else throw new Error('usage: node scripts/docs.mjs prepare|check');
} catch (e) {
  if (!(e instanceof DocsError)) throw e;
  console.error(`docs: ${e.message}`);
  process.exit(1);
}
