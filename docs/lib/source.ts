import { llms, loader } from 'fumadocs-core/source';
import { docsRoute, markdownUrl } from './shared';
import { defineDocs } from 'fumadocs-mdx/macro';
import { applyMdxPreset } from 'fumadocs-mdx/config';
import { metaSchema, pageSchema } from 'fumadocs-core/source/schema';
import { remarkDocs } from './remark-docs.mjs';
import { SITE } from '../scripts/sources.mjs';

const docs = defineDocs({
  dir: 'content/docs',
  docs: {
    schema: pageSchema,
    mdxOptions: applyMdxPreset({ remarkPlugins: [remarkDocs] }),
    postprocess: {
      includeProcessedMarkdown: true,
    },
  },
  meta: {
    schema: metaSchema,
  },
});

export const source = loader({
  baseUrl: docsRoute,
  source: docs.toFumadocsSource(),
});

export const docsLlms = llms(source, {
  // agents read the Markdown of each page, which lies beside the page
  renderUrl: (item) => {
    const url = item.url.replace(/\/$/, '');
    return url === docsRoute ? `${docsRoute}/index.md` : `${url}.md`;
  },
  renderPage: async (page) => `# ${page.data.title}

Source: ${SITE}${page.url} (Markdown: ${SITE}${markdownUrl(page)})

${page.data.description ?? ''}

${await page.data.getText('processed')}`,
});
