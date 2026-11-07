# Reach Pocketfader from anywhere with Cloudflare

Cloudflare Tunnel lets your phone reach Pocketfader over **HTTPS from any network** without opening a port
on your router, and **Cloudflare Access** puts a login screen in front of it so only you can get in.
It is an alternative to Tailscale. Use whichever you prefer; both are safe when set up as below.

```
phone ──HTTPS──▶ Cloudflare (login) ──▶ cloudflared (on your computer) ──▶ Pocketfader on 127.0.0.1:8777
```

**You need:** a free Cloudflare account, a domain whose DNS is managed by Cloudflare, and `cloudflared`
installed on the computer that runs Pocketfader. Cloudflare occasionally renames dashboard menus; if a step
looks different, search Cloudflare's docs for the same term.

## 1. Install `cloudflared`

Follow Cloudflare's download page for your system (Linux packages, `winget install Cloudflare.cloudflared`
on Windows, `brew install cloudflared` on macOS), then check it works:

```bash
cloudflared --version
```

## 2. Create the tunnel

```bash
cloudflared tunnel login                      # opens a browser; pick your domain
cloudflared tunnel create pocketfader         # prints a tunnel ID (a UUID)
cloudflared tunnel route dns pocketfader music.example.com
```

Replace `music.example.com` with the address you want to use. Then create `~/.cloudflared/config.yml`
(use the tunnel ID that was printed, and your own home folder):

```yaml
tunnel: <TUNNEL-ID>
credentials-file: /home/<you>/.cloudflared/<TUNNEL-ID>.json
ingress:
  - hostname: music.example.com
    service: http://127.0.0.1:8777
  - service: http_status:404
```

Test it in a terminal first:

```bash
cloudflared tunnel run pocketfader
```

To keep it running in the background after a reboot, follow Cloudflare's "run as a service" steps
(`sudo cloudflared service install`, which reads its config from `/etc/cloudflared/`; copy `config.yml`
and the credentials file there).

> Prefer clicking to editing files? In the Cloudflare dashboard you can create a *dashboard-managed*
> tunnel (Zero Trust → Networks → Tunnels), point its public hostname at `http://127.0.0.1:8777`, and run the
> one command it shows you.

## 3. Tell Pocketfader about the address (and require its token)

Pocketfader refuses requests for hostnames it doesn't know, which protects against DNS-rebinding tricks.
Allow your hostname, and turn on its access token as a second lock:

```bash
./install.sh --token --allow-host=music.example.com    # reinstalls the service with these options
# or, running by hand:
pocketfader --token --allow-host music.example.com
```

Pocketfader prints a link ending in `?token=…`. Open that link **once on each device**; it remembers you.
Requests that arrive through the tunnel are never treated as "local", so the token is always required for them.

## 4. Put a login in front with Cloudflare Access

In the Cloudflare dashboard open **Zero Trust → Access → Applications → Add an application → Self-hosted**:

1. **Application domain:** `music.example.com`.
2. **Policy:** *Allow* → *Emails* → your email address (Cloudflare sends a one-time code), or choose an
   identity provider you already use (Google, GitHub, …).
3. Save. Visiting `https://music.example.com` now shows a Cloudflare login first; nothing reaches your
   computer until you pass it.

With Access and the token both on, an attacker would need your Cloudflare login **and** the token.

## 5. Use it from your phone

Open `https://music.example.com/?token=…` (the link from step 3), log in when Cloudflare asks, then
use your browser's **Add to Home Screen**. Because it's real HTTPS you also get the full installed-app
experience and sharing.

## Android app and other non-browser clients

Cloudflare Access can't show a login page to an app running in the background, so apps use a **service token**:

1. Zero Trust → Access → Service Auth → **Create Service Token**; copy the *Client ID* and *Client Secret*.
2. Add a policy to your application: action **Service Auth**, include that token.
3. The app sends the headers `CF-Access-Client-Id` and `CF-Access-Client-Secret` with every request.

The WebView-based Android app in this release can sign in through Access inside its web view, but its
background media notification cannot send those headers yet, so for now use Tailscale with the app. The
native app (see the [roadmap](ROADMAP.md)) will have settings for the service-token headers.

## Troubleshooting

| You see | Cause | Fix |
|---|---|---|
| `unexpected Host header` (HTTP 421) | Pocketfader doesn't know the hostname | Start it with `--allow-host music.example.com` |
| `token required` (HTTP 401) | Missing or wrong token | Open the full `?token=…` link again |
| Cloudflare 502 / 1033 | Pocketfader or `cloudflared` isn't running | `systemctl --user status pocketfader`; run `cloudflared tunnel run pocketfader` |
| Login loop | Access policy doesn't include you | Check the policy's email / identity provider |
