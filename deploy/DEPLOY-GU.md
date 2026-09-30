# CallBridge — સર્વર પર ઇન્સ્ટોલેશન (test.akdwk.in)

આ guide થી આખી website (dashboard + API + live transcript + Whisper) તમારા સર્વર પર ચાલુ થઈ જશે.
આખું કામ **એક script** કરે છે — તમારે ફક્ત નીચેના steps copy-paste કરવાના છે.

**જરૂરી:** Ubuntu 22.04 અથવા 24.04 સર્વર, root/sudo access, ઓછામાં ઓછી 2 GB RAM (4 GB સારું).

---

## Step 1 — DNS સેટ કરો (domain → સર્વર)

તમારા domain provider (જ્યાં akdwk.in છે) માં જઈને **A record** ઉમેરો:

| Type | Name | Value |
|---|---|---|
| A | `test` | તમારા સર્વરનો IP (દા.ત. `203.0.113.10`) |

સર્વરનો IP જોવા માટે સર્વર પર: `curl -4 ifconfig.me`

DNS update થતા 5–30 મિનિટ લાગી શકે. ચેક કરવા: `ping test.akdwk.in` — IP તમારા સર્વરનો જ આવવો જોઈએ.

---

## Step 2 — સર્વરમાં login કરો

તમારા computer પરથી (Windows માં PowerShell / Mac-Linux માં Terminal):

```bash
ssh root@YOUR_SERVER_IP
```

---

## Step 3 — Code સર્વર પર લાવો

```bash
sudo apt-get update && sudo apt-get install -y git
sudo git clone -b ccr-431d290e-vhfo5s https://github.com/akshaykananidwk/Callingapp.git /opt/callbridge
cd /opt/callbridge
```

> **Repo private હોય તો** git username/password પૂછશે. Password ની જગ્યાએ GitHub **Personal Access Token** આપો:
> GitHub → Settings → Developer settings → Personal access tokens → *Generate new token* → `repo` permission.
>
> (Branch main માં merge થઈ જાય પછી `-b ccr-431d290e-vhfo5s` કાઢી નાખજો.)

---

## Step 4 — Installer ચલાવો

```bash
sudo bash deploy/install.sh
```

Script આ 4 સવાલ પૂછશે:

1. **Email** — SSL certificate માટે (તમારો email)
2. **Admin username** — Enter દબાવો તો `admin`
3. **Admin password** — ઓછામાં ઓછા 8 અક્ષર (બે વાર)
4. **Whisper model** — Enter દબાવો તો `small` (સારું balance). વધારે સારું ગુજરાતી જોઈએ અને 4+ GB RAM હોય તો `medium`

પછી script આપોઆપ કરશે:

- Nginx, PostgreSQL, Node.js 20 install
- Database બનાવે (password જાતે generate થાય)
- whisper.cpp build + model download (**5–15 મિનિટ** લાગી શકે)
- બંને services ચાલુ કરે અને restart પછી પણ આપોઆપ ચાલુ થાય એવું સેટ કરે
- Nginx + **HTTPS (SSL)** certificate

છેલ્લે આવું દેખાશે:

```
  CallBridge is installed (running)
  Dashboard : https://test.akdwk.in
```

---

## Step 5 — Token generate કરો

1. Browser માં ખોલો: **https://test.akdwk.in**
2. Step 4 માં આપેલ username / password થી login કરો
3. ઉપર **Tokens** → Name લખો (દા.ત. `Samsung S25 Ultra`) → **Generate token**
4. Token **તરત copy કરો** — ફરી દેખાશે નહીં (ખોવાઈ જાય તો નવો બનાવો, જૂનો *Revoke* કરો)

---

## Step 6 — Phone માં app સેટ કરો

1. Phone ના browser માં https://test.akdwk.in ખોલી login કરો → **Settings → Download the CallBridge APK** → install
2. App ખોલો → **Grant all permissions** → **Disable battery optimization**
3. **VPS URL:** `https://test.akdwk.in` (પહેલેથી ભરેલું હશે)
4. **API token:** Step 5 નો token paste કરો
5. **Start CallBridge** → Settings માં **Test connection** → `✓ Connected`
6. કોઈને call કરો → dashboard ના **Live** page પર transcript આવવું જોઈએ ✅

---

## રોજિંદા કામના commands

| કામ | Command |
|---|---|
| Status જોવું | `sudo systemctl status callbridge callbridge-whisper` |
| Logs જોવા (live) | `sudo journalctl -u callbridge -f` |
| Whisper logs | `sudo journalctl -u callbridge-whisper -f` |
| Restart | `sudo systemctl restart callbridge` |
| નવો code update | `cd /opt/callbridge && sudo bash deploy/update.sh` |
| Admin password ભૂલી ગયા | `cd /opt/callbridge/server && sudo -u callbridge node scripts/create-admin.js admin NEWPASSWORD` |
| Database backup | `sudo -u postgres pg_dump callbridge > ~/callbridge-$(date +%F).sql` |

Settings file: `/opt/callbridge/server/.env` (બદલ્યા પછી `sudo systemctl restart callbridge`)

---

## મુશ્કેલી આવે તો

**SSL ન બન્યું ("DNS points to …")** — Step 1 નો DNS હજી update નથી થયો. થોડી વાર રાહ જુઓ, પછી:
```bash
sudo certbot --nginx -d test.akdwk.in --redirect
sudo sed -i 's#^PUBLIC_URL=.*#PUBLIC_URL=https://test.akdwk.in#' /opt/callbridge/server/.env
sudo systemctl restart callbridge
```

**Website ખૂલે નહીં** — firewall માં 80/443 ખુલ્લા છે? `sudo ufw allow 'Nginx Full'`.
Cloud provider (AWS / DigitalOcean / Hostinger) ના panel માં પણ port 80 અને 443 allow કરવા.

**Dashboard પર Speech-to-text લાલ (red) છે** — `sudo systemctl restart callbridge-whisper` પછી
`sudo journalctl -u callbridge-whisper -n 50` જુઓ. RAM ઓછી હોય તો નાનું model વાપરો:
```bash
sudo WHISPER_MODEL=base bash deploy/install.sh
```

**Transcript ખાલી આવે છે પણ call dashboard માં દેખાય છે** — audio phone માંથી આવતો નથી.
App → Settings → Audio source માં બીજો option અજમાવો (Voice call / Mic), અથવા *Call audio helper* (Accessibility) ચાલુ કરો.

**ગુજરાતી transcript ની quality ઓછી છે** — `medium` model વાપરો, અથવા OpenAI Whisper API:
`.env` માં `STT_PROVIDER=openai` અને `OPENAI_API_KEY=sk-...` મૂકી restart કરો (per-minute ચાર્જ લાગે).

**Port conflict** — app default port `3100` વાપરે છે. બદલવા: `.env` માં `PORT=` અને
`/etc/nginx/sites-available/callbridge` માં `proxy_pass` બંને બદલો, પછી `sudo systemctl reload nginx && sudo systemctl restart callbridge`.
