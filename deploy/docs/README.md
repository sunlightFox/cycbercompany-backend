# Documentation site deployment

The Docusaurus site is built from `website/` and served directly by Nginx. The application server does not need to run Node.js after the static files have been deployed.

## First server setup

1. Create a DNS `A` record for `docs.your-domain.com` pointing to the server public IP.
2. Copy `nginx-cycbercompany-docs.conf` to `/etc/nginx/sites-available/cycbercompany-docs`, replace `docs.your-domain.com`, then enable it.
3. Copy `publish-cycbercompany-docs` to `/usr/local/bin/publish-cycbercompany-docs` and make it executable.
4. Create the initial release directory, check Nginx, and enable HTTPS.

```bash
sudo install -d -m 755 /var/www/cycbercompany-docs/releases
sudo install -m 755 deploy/docs/publish-cycbercompany-docs /usr/local/bin/publish-cycbercompany-docs
sudo ln -s /etc/nginx/sites-available/cycbercompany-docs /etc/nginx/sites-enabled/cycbercompany-docs
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d docs.your-domain.com
```

## GitHub Actions secrets

Set these repository secrets before enabling `.github/workflows/docs-deploy.yml`:

| Secret | Value |
| --- | --- |
| `DOCS_URL` | `https://docs.your-domain.com` |
| `DEPLOY_HOST` | Server IP or hostname |
| `DEPLOY_USER` | Non-root SSH deployment user |
| `DEPLOY_SSH_KEY` | Private key for that user |

The deployment user needs passwordless permission only for `/usr/local/bin/publish-cycbercompany-docs`, for example with a narrowly scoped sudoers entry:

```text
deploy ALL=(root) NOPASSWD: /usr/local/bin/publish-cycbercompany-docs *
```

Each deploy uploads the static build to `/tmp`, activates a timestamped release through the `current` symlink, validates Nginx, reloads it, and retains the most recent five releases.
