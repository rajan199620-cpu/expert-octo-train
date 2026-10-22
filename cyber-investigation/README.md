# Cyber Investigation — Tips & Tricks

A running notebook of cyber-safety and investigation tips, built up over time.
Entries marked 📝 came from handwritten notes (the photos are kept in
[`source-notes/`](source-notes/)); everything else was added to fill in context.
Items marked ⚠️ *verify* still need confirming against an official source.
For a one-page version, see [CHEATSHEET.md](CHEATSHEET.md).

**Emergency first step (India):** report cyber fraud on **1930** or at
**[cybercrime.gov.in](https://cybercrime.gov.in)** as fast as possible —
the sooner it's reported, the better the odds of freezing the money trail.

---

## Contents

1. [Fake bank SMS / WhatsApp / calls](#1-fake-bank-sms--whatsapp--calls)
2. [Call & SMS forwarding scams (USSD codes)](#2-call--sms-forwarding-scams-ussd-codes)
3. [Non-consensual intimate images — StopNCII](#3-non-consensual-intimate-images--stopncii)
4. [Breach checks — Have I Been Pwned](#4-breach-checks--have-i-been-pwned)
5. [Suspicious files & links — VirusTotal](#5-suspicious-files--links--virustotal)
6. [Mobile security tools](#6-mobile-security-tools)
7. [Government tools — Sanchar Saathi](#7-government-tools--sanchar-saathi)
8. [OSINT quick toolkit](#8-osint-quick-toolkit)
9. [Common legal provisions](#9-common-legal-provisions)
10. [Inbox — new tips to sort](#10-inbox--new-tips-to-sort)

---

## 1. Fake bank SMS / WhatsApp / calls

📝 **Bank SMS never come from a 10-digit mobile number.**
- Genuine bank/commercial SMS in India arrive from a registered **sender ID
  (header)**, e.g. `AX-HDFCBK`, `VM-SBIBNK`, `JD-ICICIT`. The first two letters
  are the operator/circle code; the part after the dash identifies the sender.
- Newer headers also carry a suffix for the message type: `-S` service,
  `-T` transactional, `-P` promotional, `-G` government.
- A "bank" SMS from `+91 98xxxxxxxx` asking you to update KYC, click a link or
  share an OTP is a scam.

📝 **WhatsApp → only official accounts.**
- Real business accounts show a **verified badge** (blue tick) and the
  business name. Anyone can put a bank logo as a profile photo — the photo
  proves nothing.
- Banks do not ask for OTP, PIN, CVV, or remote-access apps (AnyDesk,
  TeamViewer, QuickSupport) over WhatsApp.

**Calls from banks / financial institutions**
- Service/transactional calls from regulated financial entities are being
  moved to the **`1600xx` number series**; promotional calls use the
  **`140` series**. A "bank officer" calling from a normal mobile number is a
  red flag.

**Investigator checklist for a fake-bank message**
- Screenshot the full message *with* the sender number/header and timestamp.
- Expand short links (see [§8](#8-osint-quick-toolkit)) and run the final URL
  through VirusTotal / urlscan.io.
- WHOIS the domain — scam domains are usually days or weeks old.
- Report the number/message on **Chakshu** (Sanchar Saathi, [§7](#7-government-tools--sanchar-saathi)).

---

## 2. Call & SMS forwarding scams (USSD codes)

📝 **Never hand your phone to anyone.** A scammer only needs a few seconds to
dial a forwarding code. Once calls/SMS are forwarded, OTPs and bank
verification calls go to *their* number.

Typical tricks: a "courier/delivery agent" or "customer care" asks the victim
to dial a code like `*401*<number>` or `**21*<number>#` "to confirm the
order" — that code actually turns on unconditional call forwarding.

📝 **Standard GSM codes** (work on most phones/networks; dial like a number):

| Code | What it does |
|---|---|
| `*#21#` | 📝 **Check** unconditional call forwarding status |
| `##21#` | 📝 **Remove** unconditional call forwarding |
| `*#61#` | Check forwarding when unanswered |
| `*#62#` | Check forwarding when unreachable / switched off |
| `*#67#` | Check forwarding when busy |
| `##002#` | Cancel **all** call forwarding (handy to "prevent diversion" in one go) |
| `**21*<number>#` | *Activates* unconditional forwarding — the code scammers ask you to dial |
| `*#06#` | Show the phone's IMEI |

Notes
- Some operators use their own codes (e.g. Jio's `*401*<number>`), and
  operator apps also show forwarding status — check both if unsure.
- SMS forwarding is usually done through a malicious app or a compromised
  messaging/Google account, not a USSD code. Check *Settings → Apps* for
  unknown apps with SMS permission, and check linked devices in WhatsApp /
  Google Messages.
- After any suspected compromise: run `##002#`, change bank/UPI PINs, and
  ask the bank to block the account/cards.

---

## 3. Non-consensual intimate images — StopNCII

📝 **[StopNCII.org](https://stopncii.org)**
- For adults (18+) whose intimate images are shared, or threatened to be
  shared, without consent (sextortion, revenge porn).
- The image is **hashed on the victim's own device** — the photo itself is
  never uploaded. Only the hash goes to partner platforms (Facebook,
  Instagram, TikTok, Reddit and others), which then block matching uploads.
- Keep the case number it gives you to track or withdraw the case.
- For someone **under 18**, use NCMEC's **[Take It Down](https://takeitdown.ncmec.org)**
  instead.
- Also report on cybercrime.gov.in, which has a dedicated section for women-
  and child-related crimes.

---

## 4. Breach checks — Have I Been Pwned

📝 **[haveibeenpwned.com](https://haveibeenpwned.com)**
- Enter an email address (or phone number) to see which known data breaches
  it appears in, and what data leaked (passwords, phone, address…).
- **Pwned Passwords** checks whether a password has appeared in breaches
  without sending the password itself.
- Investigation use: explains *how* a scammer got a victim's details — e.g.
  a leaked email + phone from a shopping-site breach used for targeted
  phishing.
- Advice to victims: change reused passwords, turn on 2-factor
  authentication, use a password manager.

---

## 5. Suspicious files & links — VirusTotal

📝 **[virustotal.com](https://www.virustotal.com)**
- Scan a **file, URL, domain, IP address, or file hash** against ~70
  antivirus engines and URL blocklists.
- Common case: a fake "bank KYC" or "wedding invitation" **APK** sent on
  WhatsApp — scan it before anything else.
- ⚠️ Files uploaded to VirusTotal are **shared with security researchers**.
  Never upload private documents or case evidence containing personal data —
  search by the file's **SHA-256 hash** instead (`sha256sum file` on
  Linux/Mac, `certutil -hashfile file SHA256` on Windows).
- Useful tabs: *Relations* (linked domains/IPs), *Details* (APK permissions,
  signing certificate), *Community*.

---

## 6. Mobile security tools

📝 **MobiArmour** — ⚠️ *verify*: noted as a mobile security tool; confirm the
official source/publisher before recommending it, since fake "security"
apps are themselves a common malware vector.

General checks on a suspected-compromised Android phone
- *Settings → Apps → Special app access*: look for unknown apps with
  **Accessibility**, **Device admin**, **Display over other apps**, or
  **SMS** access — banking trojans need these.
- Google Play Protect scan; uninstall apps installed from outside the Play
  Store (sideloaded APKs).
- WhatsApp → *Linked devices*; Google account → *Security → Your devices*.

---

## 7. Government tools — Sanchar Saathi

**[sancharsaathi.gov.in](https://sancharsaathi.gov.in)** (DoT) — also an app.

| Module | Use |
|---|---|
| **Chakshu** | Report suspected fraud calls, SMS and WhatsApp messages |
| **Know Mobile Connections in Your Name** (TAFCOP) | See every SIM issued on your ID; report ones you don't own |
| **Block Your Lost/Stolen Mobile** (CEIR) | Block a stolen phone by IMEI so it can't be used on Indian networks |
| **Know Genuineness of Your Mobile** | Check whether an IMEI is genuine before buying a used phone |
| **Report Incoming International Call with Indian Number** | Flag spoofed "+91" calls coming from abroad |

---

## 8. OSINT quick toolkit

| Need | Tool / trick |
|---|---|
| Inspect a link without visiting it | [urlscan.io](https://urlscan.io), VirusTotal URL scan |
| Expand a short link | [unshorten.it](https://unshorten.it), or `curl -sIL <url>` and read the `Location` headers |
| Domain age & registrar | WHOIS ([who.is](https://who.is), [lookup.icann.org](https://lookup.icann.org)) |
| Old version of a page / deleted page | [Wayback Machine](https://web.archive.org) |
| Where a photo came from | Google Lens, Bing Visual Search, Yandex Images, TinEye |
| Photo metadata (camera, date, GPS) | EXIF viewers; note WhatsApp/Instagram strip EXIF, so ask for the *original* file |
| Real origin of an email | Read the full headers ("Show original" in Gmail); trace the first `Received:` hop and check SPF/DKIM results |
| IP address owner/location | [ipinfo.io](https://ipinfo.io), WHOIS on the IP |

---

## 9. Common legal provisions

Quick pointers only — always read the current bare act before relying on these.

| Situation | Provision |
|---|---|
| Identity theft (using someone's password, OTP, signature) | IT Act §66C |
| Cheating by impersonation using a computer/phone | IT Act §66D |
| Capturing/sharing private images without consent | IT Act §66E |
| Publishing obscene / sexually explicit material online | IT Act §67, §67A (§67B for child sexual abuse material) |
| Cheating (general) | BNS §318 |

---

## 10. Inbox — new tips to sort

Drop new tips here; they get moved into the right section later.

- 
