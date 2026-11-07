# JJ's Tools stash sync server

A small server that lets the Control Panel's Stash Manager share stashes between players. It is
one Python file and needs nothing installed apart from Python 3.8 or newer, which almost every
Linux VPS already has.

Everyone whose Control Panel uses the same token sees the same stashes. Containers are merged one
at a time, the newest look at each winning, so two people opening chests at once never wipe each
other's work.

## Setting it up on a VPS (Ubuntu or Debian)

Any small VPS is plenty: 1 CPU and 512 MB of memory will do. These steps assume you can log in
with SSH as a user that can use `sudo`.

1. **Copy the files up.** From the folder with this README, on your own computer:

   ```
   scp jjs-sync-server.py jjs-sync.service youruser@your.vps.ip:~
   ```

2. **Log in and make a user and folder for it:**

   ```
   ssh youruser@your.vps.ip
   sudo useradd --system --home /opt/jjs-sync --shell /usr/sbin/nologin jjsync
   sudo mkdir -p /opt/jjs-sync/stash-data
   sudo mv ~/jjs-sync-server.py /opt/jjs-sync/
   ```

3. **Make a token.** This prints a long random one; copy it somewhere safe:

   ```
   python3 -c "import secrets; print(secrets.token_urlsafe(32))"
   ```

   Then save it for the server (paste yours in place of the example):

   ```
   echo 'JJS_TOKEN=paste-your-token-here' | sudo tee /opt/jjs-sync/token.env
   sudo chown -R jjsync:jjsync /opt/jjs-sync
   sudo chmod 600 /opt/jjs-sync/token.env
   ```

4. **Run it as a service**, so it starts with the VPS and restarts if it stops:

   ```
   sudo mv ~/jjs-sync.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now jjs-sync
   sudo systemctl status jjs-sync
   ```

   It should say `active (running)`.

5. **Open the port** if the VPS has a firewall:

   ```
   sudo ufw allow 8787/tcp
   ```

   Some providers (Oracle, AWS, Google Cloud) also have a firewall in their website; allow TCP
   port 8787 there too.

6. **Check it from your own computer.** Open `http://your.vps.ip:8787/health` in a browser. It
   should show `{"ok": true}`.

7. **Point the Control Panel at it.** In Meteor, open the Control Panel module's settings and,
   under Stash Manager, set:
   - `sync-mode` to `Server`
   - `sync-server` to `http://your.vps.ip:8787`
   - `sync-token` to your token

   The stash manager's bottom line shows "synced 3s ago" once it is working. Your existing stashes
   are uploaded the first time. Give your friends the same address and token.

## HTTPS (recommended)

Over plain `http://`, the token and the stashes travel unencrypted, so anyone on the network in
between could read them. If you have a domain, Caddy adds HTTPS for free in a few lines:

1. Point a subdomain, such as `stash.yourdomain.com`, at the VPS's IP with an A record.
2. Install Caddy: https://caddyserver.com/docs/install
3. Put this in `/etc/caddy/Caddyfile`:

   ```
   stash.yourdomain.com {
       reverse_proxy 127.0.0.1:8787
   }
   ```

4. Make the sync server only listen locally. In `/etc/systemd/system/jjs-sync.service`, add
   `--host 127.0.0.1` to the end of the `ExecStart` line. Then:

   ```
   sudo systemctl daemon-reload
   sudo systemctl restart jjs-sync caddy
   sudo ufw allow 443/tcp
   sudo ufw delete allow 8787/tcp
   ```

5. Use `https://stash.yourdomain.com` as the `sync-server` in the Control Panel.

## Looking after it

- **Logs:** `sudo journalctl -u jjs-sync -f`
- **Backups:** everything is in `/opt/jjs-sync/stash-data`, one JSON file per stash. Copy that
  folder to back it up.
- **Changing the token:** edit `/opt/jjs-sync/token.env`, then
  `sudo systemctl restart jjs-sync`, and give everyone the new one.
- **Updating:** copy a new `jjs-sync-server.py` over the old one, then
  `sudo systemctl restart jjs-sync`.
