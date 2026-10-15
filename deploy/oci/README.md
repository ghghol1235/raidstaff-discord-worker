# Oracle Cloud Always Free VM deployment

This runs the Discord worker on an Ubuntu 24.04 Oracle Cloud VM. The Minecraft server and the computer at home can be offline while the VM is running. Use only resources marked **Always Free** in the OCI console; do not enable paid shapes or paid add-ons. Free capacity is not guaranteed, and OCI may reclaim idle Always Free instances.

## 1. Create the VM in OCI

Create an Ubuntu 24.04 VM in your tenancy's home region. Choose an Always Free eligible shape and check the estimated monthly cost is $0 before creating it. Assign a reserved public IPv4 address so DNS does not change. Permit inbound TCP 22 for your IP, and TCP 80/443 for the HTTPS bridge. Do not expose port 10000 publicly; the service binds only to localhost.

## 2. Prepare DNS and HTTPS

Create a DNS A record such as `raidstaff.example.net` pointing to the VM's reserved IPv4 address. Copy `deploy/oci/raidstaff-worker.nginx.conf` to `/etc/nginx/sites-available/raidstaff-worker`, replace the example hostname with your domain, enable it with a symlink in `/etc/nginx/sites-enabled/`, run `sudo nginx -t && sudo systemctl reload nginx`, then run `sudo certbot --nginx -d raidstaff.example.net`. Set the Paper plugin's bridge URL to `https://raidstaff.example.net`. Both the OCI network security list and Ubuntu firewall must allow TCP 80/443. Keep TLS verification enabled.

## 3. Install Java, Maven, and the worker

```bash
sudo apt update
sudo apt install -y openjdk-21-jre-headless maven git nginx certbot python3-certbot-nginx
sudo useradd --system --home /var/lib/raidstaff --create-home --shell /usr/sbin/nologin raidstaff
sudo git clone https://github.com/ghghol1235/raidstaff-discord-worker.git /opt/raidstaff-discord-worker
cd /opt/raidstaff-discord-worker
mvn -B -DskipTests package
sudo chown -R root:root /opt/raidstaff-discord-worker
sudo install -o root -g root -m 600 deploy/oci/worker.env.example /etc/raidstaff-worker.env
sudo nano /etc/raidstaff-worker.env
```

Replace the placeholders in `/etc/raidstaff-worker.env` with the Discord bot token and a newly generated long random bridge secret. Keep both secrets on the VM; do not put them in GitHub, this chat, or the Paper config except for the bridge secret. The Paper plugin must use the same bridge secret.

Install and start the service:

```bash
sudo install -m 644 deploy/oci/raidstaff-worker.service /etc/systemd/system/raidstaff-worker.service
sudo systemctl daemon-reload
sudo systemctl enable --now raidstaff-worker
sudo systemctl status raidstaff-worker
```

## 4. Configure the Paper plugin

After HTTPS works, set `standalone-worker.enabled: true`, `bridge.url: 'https://raidstaff.example.net'`, and `bridge.token` to the same bridge secret. Restart Paper and check its RaidStaff logs. Preserve the original `staff-data.json` until the worker confirms the first import.

## Operations

View logs with `sudo journalctl -u raidstaff-worker -f`; restart after an update with `sudo systemctl restart raidstaff-worker`. To update the code, pull the repository, rebuild, then restart. Before updating, back up `/var/lib/raidstaff/staff-data.json`.

This guide does not create an OCI account or VM. Account signup, home-region selection, SSH key handling, and any required identity checks must be completed in the OCI console by the account owner. Verify the resource says **Always Free** and its estimate is $0 before creation.
