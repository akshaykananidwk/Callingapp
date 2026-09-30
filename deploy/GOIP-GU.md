# GoIP + વેબસાઈટ Calling — Setup (ગુજરાતી)

આ setup પછી: SIM **GoIP gateway** માં રહેશે, અને **આખું calling વેબસાઈટ પરથી** થશે —
ફોન વાગે તો વેબસાઈટ પર વાગે, ત્યાંથી જ ઉપાડો અને computer ના mic/speaker થી વાત કરો,
કોઈને પણ number લગાડો, દરેક call આપોઆપ record + live transcript થાય.
Auto-answer વખતે demo clip **સીધી phone line માં** જાય — સામેવાળાને બરાબર સંભળાય.

```
 સામેવાળો ફોન ──GSM──▶ GoIP (તમારું SIM) ──internet/SIP──▶ Asterisk (test.akdwk.in) ──WebRTC──▶ વેબસાઈટ (browser)
```

---

## 1. કયું GoIP લેવું — ખાસ ધ્યાન

| તમારું SIM | કયું gateway |
|---|---|
| **Jio** | Jio ફક્ત 4G/VoLTE છે → **"4G VoLTE" SIP gateway** જ ચાલશે |
| Airtel / Vi / BSNL | 2G GoIP-1 પણ ચાલે, પણ 2G બંધ થતું જાય છે → **4G VoLTE મોડલ લેવું વધારે સલામત** |

ખરીદતી વખતે દુકાનદારને પૂછો: **"1 port, SIP support, 4G VoLTE, India SIM supported"**.

> ⚖️ Commercial ઉપયોગ માટે SIM gateway વિશે DoT ના નિયમો ચકાસી લેજો.

---

## 2. Server પર PBX install (એક વાર)

SSH કરીને:

```bash
cd /opt/callbridge
bash deploy/update.sh
bash deploy/pbx-install.sh
```

છેલ્લે આવું box આવશે — **Password લખી રાખો** (Settings page પર પણ દેખાશે):

```
  GoIP settings (web panel → Configurations → Basic VoIP):
    SIP Server/Registrar : test.akdwk.in      Port: 5060
    Phone Number         : goip
    Authentication ID    : goip
    Password             : xxxxxxxxxxxxxxxx
```

---

## 3. GoIP માં setting

1. SIM GoIP માં નાખો, antenna લગાવો, LAN cable **router** માં લગાવો, power ચાલુ કરો.
2. GoIP નો IP શોધો: router ના admin page માં "Connected devices / DHCP list" માં GoIP દેખાશે.
3. Browser માં `http://GOIP_IP` ખોલો → login (મોટે ભાગે `admin` / `admin`).
4. **Configurations → Basic VoIP**:
   - Config Mode: **Single Server** (અથવા "SIP")
   - SIP Registrar Server / SIP Proxy Server: **test.akdwk.in**
   - Port: **5060**
   - Phone Number: **goip**
   - Authentication ID / User: **goip**
   - Password: *(ઉપરનો password)*
   - Save / Apply
5. **Call In** (GSM → VoIP):
   - Forward to VoIP: **ON**, Forward Number: **s** (અથવા `1000` — કોઈ પણ ચાલશે)
   - Call In Authentication: **None / Disable**
6. **Call Out** (VoIP → GSM): Authentication Mode: **None**
7. **Media / Codec** (જો option હોય): **PCMU (G.711u)** પહેલું, પછી PCMA.
8. Save → GoIP reboot.

> મેનુના નામ firmware પ્રમાણે થોડા અલગ હોઈ શકે — જે નામ મળતું આવે એ પસંદ કરો.

**ચેક:** વેબસાઈટ → **Phone** page પર "GSM gateway (GoIP): **Online**" (લીલું) દેખાવું જોઈએ.

---

## 4. વેબસાઈટ પર calling

1. https://test.akdwk.in → login → **Phone**
2. **🔔 Enable sound & alerts** દબાવો → browser mic માટે પૂછે તો **Allow**
3. "Web phone: **Ready**" લીલું દેખાવું જોઈએ
4. **ફોન કરવો:** number લખો → **📞 Call**
5. **ફોન આવે:** popup + ringtone → **Answer** → headphone/speaker થી વાત કરો
6. Call ચાલુ હોય ત્યારે keypad દબાવો તો IVR માટે tone જાય (દા.ત. "1 દબાવો")

**"When a call comes in" (Phone page અથવા Settings):**

| Option | શું થાય |
|---|---|
| Ring the website only | ફક્ત વેબસાઈટ પર વાગે; કોઈ ન ઉપાડે તો **Missed** |
| Ring the website, then auto-answer with demo clip | પહેલાં વેબસાઈટ પર વાગે (default 20 સેકન્ડ), કોઈ ન ઉપાડે તો **આપોઆપ ઉપડે + demo clip વાગે** |
| Auto-answer immediately with demo clip | તરત ઉપડે + demo clip loop માં વાગે (testing માટે) |

**Demo clip:** Settings → Demo clip → **● Record** (browser માં બોલો) અથવા **Choose file** (mp3/m4a/wav).

**Recording:** Calls → call ખોલો → ▶ player + Download.

---

## 5. મુશ્કેલી આવે તો

| સમસ્યા | ઉપાય |
|---|---|
| GoIP "Not connected" | GoIP માં server/username/password ફરી ચકાસો; GoIP ને internet મળે છે? `sudo asterisk -rx "pjsip show contacts"` |
| Web phone "Login failed" | Page refresh કરો; `sudo systemctl restart asterisk` |
| Call લાગે પણ અવાજ નથી આવતો | Server firewall માં UDP 10000–20000 ખુલ્લા હોવા જોઈએ (`ufw status`); browser ને mic permission આપી છે? |
| સામેવાળાને તમારો અવાજ નથી જતો | Browser નું address bar → 🔒 → Microphone → Allow |
| Asterisk logs | `sudo tail -f /var/log/asterisk/messages*` |
| Call events | `sudo journalctl -u callbridge -f` (`[pbx] incoming / end` લાઈનો) |

Brute-force હુમલાથી બચવા **fail2ban** આપોઆપ ચાલુ થાય છે (5 ખોટા password → 24 કલાક block).
