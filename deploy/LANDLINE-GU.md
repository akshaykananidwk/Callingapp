# Landline → વેબસાઈટ Calling (Grandstream HT813) — ગુજરાતી

Landline નો વાયર **HT813** નામના નાના box માં લગાડો → એ internet થી તમારા server (Asterisk) સાથે જોડાય →
વેબસાઈટ પરથી જ ફોન આવે / જાય, recording + live transcript, auto-answer + demo clip (સીધી line માં).

```
Landline વાયર ──▶ HT813 (FXO port) ──LAN/internet──▶ Asterisk (test.akdwk.in) ──WebRTC──▶ વેબસાઈટ
```

> **ધ્યાન:** HT813 માં બે port છે — **FXO** (landline વાયર માટે) અને **FXS** (સાદો ફોન સેટ લગાડવા માટે, optional).
> Landline હંમેશાં **FXO** માં જ લગાડવો.

---

## 1. શું જોઈએ

| વસ્તુ | નોંધ |
|---|---|
| Grandstream **HT813** | Amazon / telecom દુકાન (આશરે ₹6–9 હજાર). **HT813** જ લેવું — HT801/HT802 માં FXO નથી |
| Landline | તાંબાનો (BSNL/MTNL) સીધો વાયર, **અથવા** fiber router નો "Phone" port |
| LAN cable | HT813 → તમારું internet router |
| SIM વાપરવું હોય તો | **4G VoLTE FCT** (Fixed Wireless Terminal, SIM વાળું, RJ11 port) → એનો port HT813 ના FXO માં |

---

## 2. Server પર (એક વાર)

```bash
cd /opt/callbridge
bash deploy/update.sh
bash deploy/pbx-install.sh
```
છેલ્લે **Password** દેખાશે (વેબસાઈટ → Settings → Phone line gateway માં પણ મળશે).

---

## 3. HT813 જોડો

1. Landline વાયર → HT813 ના **FXO** port માં
2. LAN cable → HT813 ના **WAN/LAN** port થી router માં
3. Power ચાલુ કરો
4. HT813 નો IP જાણો: FXS port માં સાદો ફોન સેટ લગાડી **`***`** દબાવો → **`02`** → IP બોલીને સંભળાવશે
   (અથવા router ના "Connected devices" list માં જુઓ)
5. Browser માં `http://HT813_IP` → login (default password HT813 ના નીચે sticker પર / `admin`)

---

## 4. HT813 settings

**FXO Port** tab (અથવા "FXO Port" page):

| Setting | Value |
|---|---|
| Account Active | **Yes** |
| Primary SIP Server | **test.akdwk.in** |
| SIP Transport | UDP, port **5060** |
| NAT Traversal | **Keep-Alive** (અથવા STUN) |
| SIP User ID | **goip** |
| Authenticate ID | **goip** |
| Authenticate Password | *(server નો password)* |
| SIP Registration | **Yes** |
| Preferred Vocoder | **PCMU** પહેલું, પછી PCMA |
| DTMF | **RFC2833** |
| Stage Method (1/2) | **1** (વેબસાઈટ પરથી સીધો number લાગે) |
| Unconditional Call Forward to VoIP | User ID: **s**, SIP Server: **test.akdwk.in**, Port 5060 |
| Number of Rings | **1** |
| Caller ID Scheme | **DTMF** (BSNL/India) — number ન દેખાય તો Bellcore અજમાવો |
| Enable Current Disconnect | **Yes** |
| Enable PSTN Disconnect Tone Detection | **Yes** |
| AC Termination / Country | **India** (અથવા "Country-based" → India) જો option હોય |

**Apply → Reboot.**

> Firmware પ્રમાણે નામ થોડા અલગ હોઈ શકે — મળતું આવતું option પસંદ કરો.

**ચેક:** વેબસાઈટ → **Phone** page પર "Phone line gateway: **Online**" (લીલું).

---

## 5. વાપરવું

1. https://test.akdwk.in → **Phone** → **🔔 Enable sound & alerts** → mic **Allow**
2. **ફોન આવે:** landline પર કોઈ call કરે → વેબસાઈટ પર popup + ringtone → **Answer**
3. **ફોન કરવો:** number લખો → **📞 Call** (landline માંથી જશે)
4. **Auto-answer + demo:** Phone page → "When a call comes in" → *Ring the website, then auto-answer with demo clip*
5. **Recording:** Calls → call ખોલો → ▶

---

## 6. મુશ્કેલી આવે તો

| સમસ્યા | ઉપાય |
|---|---|
| Gateway "Not connected" | HT813 માં server/User ID/password ફરી ચકાસો; HT813 Status page પર "Registered" છે? |
| ફોન આવે પણ વેબસાઈટ પર ન વાગે | "Unconditional Call Forward to VoIP" ભર્યું છે? Number of Rings = 1? |
| Call કપાયા પછી પણ line busy રહે | Current Disconnect + Disconnect Tone Detection ON કરો |
| Caller number નથી દેખાતો | Caller ID Scheme બદલો (DTMF ↔ Bellcore/FSK); landline પર Caller ID service ચાલુ છે? |
| અવાજ ધીમો / echo | HT813 માં FXO "Gain" (RX/TX) ને 0 → -3 / +3 કરી જુઓ |
| Server logs | `sudo tail -f /var/log/asterisk/messages*` · `sudo journalctl -u callbridge -f` |

Landline ને આ રીતે જોડવું એ સામાન્ય office PBX setup છે — તમારી પોતાની line તમારા જ calls માટે.
