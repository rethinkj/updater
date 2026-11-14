// Puts the code of the examples into the pages, as scripts/sources.mjs finds it, both in the HTML
// and in the Markdown for agents.
import { replaceSource } from 'fumadocs-core/mdx-plugins/stringifier';
import { visit } from 'unist-util-visit';
import { cleanMeta, resolveFence } from '../scripts/sources.mjs';

export function remarkDocs() {
  return (tree, file) => {
    visit(tree, 'code', (node) => {
      const where = `${file.path}:${node.position?.start.line}`;
      const resolved = resolveFence(node.meta, where);
      if (!resolved) return;
      node.value = resolved.code;
      node.meta = cleanMeta(node.meta);
      replaceSource(file, node, `\`\`\`${node.lang ?? ''}\n${resolved.code}\n\`\`\``);
    });
  };
}
