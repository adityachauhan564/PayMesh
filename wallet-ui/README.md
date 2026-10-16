# wallet-ui

Angular 21 front end for PayMesh. Needs Node 20.19+ or 22.12+.

```bash
npm ci                      # install
npm start                   # dev server on http://localhost:4200, proxies /api to the gateway on :8080
npm test -- --watch=false   # unit tests (Vitest + jsdom)
npx ng build                # production build into dist/wallet-ui/browser
```

In Docker, `wallet-ui/Dockerfile` serves the build with nginx and proxies `/api` to the gateway (see `nginx.conf`).
