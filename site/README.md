# eacl.dev publishing

The website renders the repository's root `README.md`. Edit that file on `main`
to update both GitHub and eacl.dev; no website copy of its content is maintained.

The Pages workflow builds static HTML with GitHub-style heading anchors and
tables. Relative documentation links point to files on GitHub, and relative
images use GitHub's raw content host. Styling and the page shell live here.

To build locally, run `npm ci --prefix site` and `npm run build --prefix site`
from the repository root. Output goes to ignored `target/site/`.

GitHub Pages must use **GitHub Actions** as its publishing source, with the
custom domain `eacl.dev`. The `github-pages` environment must allow deployments
from `main`. The workflow runs when the README, site files, or workflow change
on `main`, and can also be run manually. The old `docs` branch publishing source
and `docs/_config.yml` are no longer used to build the website.
