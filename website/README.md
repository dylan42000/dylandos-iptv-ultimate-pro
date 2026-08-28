# Dylandos IPTV Ultimate — beta website

This folder is the static product and beta-access site for the current release tracks:

- Windows: `5.2.0`
- Android / Fire TV: `5.2.7`

It accurately describes the player as a bring-your-own-provider app; it does not offer IPTV channels, playlists, subscriptions, or access to copyrighted content.

## Preview locally

From the repository root, run `npx serve website -p 8085`, then open `http://localhost:8085`.

## Beta portal

The sign-up, sign-in, beta request, and portal UI work immediately as a browser-local prototype. That lets the design be reviewed without collecting user information on an unsecured static host.

Before publishing the beta form, set `data-beta-endpoint` on the `<body>` in `index.html` to a secure HTTPS endpoint that accepts a JSON `POST`. The endpoint must validate input, obtain consent, store passwords only with a proper authentication provider (never the browser-local prototype), and notify the beta team. Do not collect IPTV credentials, playlist URLs, or private content information.

## Assets

Images in `images/` are product screenshots and app artwork from this repository. They are intentionally used instead of stock imagery so the site reflects the actual product.
