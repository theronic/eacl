import { readFile, mkdir, writeFile, copyFile } from 'node:fs/promises';
import { marked } from 'marked';
import { gfmHeadingId } from 'marked-gfm-heading-id';

const root = new URL('../', import.meta.url);
const output = new URL('target/site/', root);
const repository = 'https://github.com/theronic/eacl';

marked.use(gfmHeadingId(), {
  walkTokens(token) {
    if (!['link', 'image'].includes(token.type)) return;
    const href = token.href;
    if (!href || href.startsWith('#') || /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(href)) return;
    // The README is the only published page; other repository files stay on GitHub.
    const base = token.type === 'image'
      ? 'https://raw.githubusercontent.com/theronic/eacl/main/'
      : `${repository}/blob/main/`;
    token.href = new URL(href.replace(/^\//, ''), base).href;
  }
});

const readme = await readFile(new URL('README.md', root), 'utf8');
const template = await readFile(new URL('template.html', import.meta.url), 'utf8');
await mkdir(output, { recursive: true });
await writeFile(new URL('index.html', output), template.replace('<!-- README -->', () => marked.parse(readme)));
await copyFile(new URL('node_modules/github-markdown-css/github-markdown.css', import.meta.url), new URL('markdown.css', output));
await writeFile(new URL('.nojekyll', output), '');
console.log(`Rendered README.md to ${output.pathname}`);
