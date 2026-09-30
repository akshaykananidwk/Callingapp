# મોબાઈલને જ Gateway બનાવો (OnePlus 9) — ગુજરાતી

**ધ્યેય:** કોઈ વધારાનું device નહીં — SIM વાળો મોબાઈલ જ gateway બને:
ફોન આપોઆપ ઉપડે, વેબસાઈટ પરથી વાત થાય, demo clip **સામેવાળાને સંભળાય**, બંને બાજુનું recording.

```
સામેવાળો ──GSM──▶ OnePlus 9 (root + gsm2sip) ──WiFi/SIP──▶ Asterisk (test.akdwk.in) ──WebRTC──▶ વેબસાઈટ
```

---

## સાદી રીતે કેમ નથી થતું? (research નું પરિણામ)

- સામાન્ય app call માં audio **નથી મોકલી શકતું** — Android એ API 21 (2014) થી બંધ કર્યું છે. Speaker પર વગાડીએ તો
  ફોનનું echo-cancellation એને કાપી નાખે છે (તમે test માં જોયું એ જ).
- **Qualcomm chip** વાળા ફોનમાં અંદર "**incall_music**" નામનો digital રસ્તો છે, જેનાથી audio સીધું call line માં જાય.
  પણ એ વાપરવા **root + system app** જોઈએ. આ જ રીતે open-source project
  **[gsm2sip](https://github.com/pulpoff/gsm2sip)** મોબાઈલને GSM→SIP gateway બનાવે છે.
- **Samsung S25 Ultra:** One UI 8 માં Samsung એ bootloader unlock **કાયમ માટે બંધ** કર્યું છે → root શક્ય નથી → આ રીત S25 પર **નહીં ચાલે**.
- **OnePlus 9 (Snapdragon 888 = Qualcomm):** bootloader તરત unlock થાય, root થઈ શકે → **આ ફોન વાપરો.**

---

## Step 0 — Root પહેલાં ચકાસો (5 મિનિટ, કંઈ બગડે નહીં)

1. OnePlus 9 માં **CallBridge v1.3.0** install કરો
2. App → **Settings** → નીચે **"Use this phone as a line gateway"** → **Run line gateway check**
3. પરિણામ:
   - ✅ **"Good candidate"** → આગળ વધો
   - ❌ **"Not possible"** → આ ફોનમાં digital રસ્તો નથી; root કરવાથી ફાયદો નહીં — બીજો Qualcomm ફોન અજમાવો
   - ❓ **"Unknown"** → computer થી ચકાસો: `adb shell "grep -rl incall_music_uplink /vendor/etc"` — કોઈ file દેખાય તો ✅

---

## Step 1 — OnePlus 9 root કરો

> ⚠️ **ધ્યાન:** bootloader unlock કરવાથી ફોનનો **બધો data ભૂંસાઈ જશે**, warranty જઈ શકે, અને banking apps કદાચ ન ચાલે.
> **આ ફોનને ફક્ત gateway તરીકે જ રાખો** (બીજા કામ માટે નહીં).

1. Settings → About → **Build number** 7 વાર દબાવો → Developer options
2. Developer options → **OEM unlocking ON** + **USB debugging ON**
3. Computer માં Android platform-tools (adb/fastboot) → ફોન USB થી જોડો
4. `adb reboot bootloader` → `fastboot oem unlock` → ફોનમાં Volume થી "Unlock" પસંદ કરો (data wipe થશે)
5. **Magisk** થી root: OxygenOS નો boot.img કાઢી Magisk app માં patch કરો → `fastboot flash boot magisk_patched.img`
   વિગતવાર guide: [awesome-android-root — OnePlus](https://awesome-android-root.org/rooting-guides/how-to-root-oneplus-phone),
   [XDA — OnePlus 9 Magisk](https://xdaforums.com/t/guide-magisk-unlock-root-keep-root-oos-14-0-0-701.4252373/)

---

## Step 2 — gsm2sip install

1. [gsm2sip releases](https://github.com/pulpoff/gsm2sip/releases) માંથી **gateway-magisk.zip** ડાઉનલોડ કરો
2. Magisk → **Modules** → Install from storage → zip પસંદ કરો → **Reboot**
3. Settings → Apps → Default apps → **Phone app** → gsm2sip ("Gateway") પસંદ કરો
4. ફોનને **WiFi + ચાર્જર** પર કાયમ રાખો

---

## Step 3 — gsm2sip ને આપણા server સાથે જોડો

પહેલાં server પર PBX install થયેલું હોવું જોઈએ:
```bash
cd /opt/callbridge && bash deploy/update.sh && bash deploy/pbx-install.sh
```

gsm2sip app → **Settings**:

| Field | Value |
|---|---|
| Server | **test.akdwk.in** |
| Port | **5060** |
| Username | **goip** |
| Password | વેબસાઈટ → Settings → **Phone line gateway** → Show |
| Own Number | તમારો SIM number, **+91** સાથે (દા.ત. `+919876543210`) |
| Codec | **G.722 only** (default, HD) — અવાજમાં તકલીફ હોય તો "G.711 only" |

**ચેક:** વેબસાઈટ → Phone page → **"Phone line gateway: Online"** (લીલું)

---

## Step 4 — વાપરો

- SIM પર કોઈ call કરે → ફોન આપોઆપ ઉપડે → વેબસાઈટ પર popup → **Answer** → computer થી વાત
- Phone page → "When a call comes in" → **auto-answer with demo clip** → demo **સીધું line માં** — સામેવાળાને સંભળાશે
- વેબસાઈટ પરથી number લગાડો → OnePlus 9 માંથી call જશે
- Calls → recording + transcript

---

## ધ્યાન રાખો

- gsm2sip ની યાદીમાં OnePlus 9 હજી "tested" નથી (Poco X3 NFC, Galaxy S4 Mini tested છે). Step 0 ✅ આવે તો ચાલવાની
  શક્યતા સારી છે, પણ **guarantee નથી** — gsm2sip માં `tools/check-device.sh` (root પછી) વધુ ઊંડું ચકાસે છે.
- gsm2sip અલગ project છે (આપણો code નથી) — એના સવાલ/ભૂલ માટે એના GitHub પર issue કરો.
- Server બાજુ (Asterisk) માં gsm2sip માટે G.722 અને `X-GSM-Forward` header પહેલેથી ઉમેરી દીધા છે.
