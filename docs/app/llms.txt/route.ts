import { docsLlms, source } from '@/lib/source';

export const revalidate = false;

export async function GET() {
  const tree = source.getPageTree();
  const sections = await Promise.all(tree.children.map((node) => docsLlms.indexNode(node)));
  return new Response(`# rethinkj Updater

> A Java 25 library that checks for updates of desktop apps (GitHub Releases, GitLab, Gitea, Forgejo or a JSON manifest over HTTP), downloads and verifies them, reads their release notes, and applies them on Windows and Linux through a small helper process. It is early: nothing is implemented yet, and these pages describe the intended design.

Every page below is Markdown: the links lead to it. The pages as HTML are at the same address without \`.md\`, and [/llms-full.txt](/llms-full.txt) is all pages in one file.

${sections.join('\n')}
`);
}
