# Cyber Cheat Sheet (compact)

Full notes: [README.md](README.md) · ⚠️ = verify before relying on it

**Fraud happened?** → Call **1930** / report at **cybercrime.gov.in** immediately.

## Spot the fake
- **Bank SMS** → comes from a sender ID like `AX-HDFCBK`, **never a 10-digit number**. Suffix shows type: `-S`/`-T`/`-P`/`-G` ⚠️
- **WhatsApp** → official accounts only (verified tick). A bank logo as the profile photo proves nothing.
- **Bank calls** → `1600xx` series; promo calls `140` series ⚠️. A bank officer calling from a normal mobile number = red flag.
- Banks never ask for OTP / PIN / CVV / remote-access apps (AnyDesk etc.).

## Call forwarding — never hand your phone to anyone
| Code | Action |
|---|---|
| `*#21#` | Check forwarding |
| `##21#` | Remove forwarding |
| `##002#` | Cancel **all** forwarding |
| `*#61#` `*#62#` `*#67#` | Check no-answer / unreachable / busy forwarding |
| `**21*no#`, `*401*no` | 🚩 What scammers ask you to dial (this *turns on* forwarding) |
| `*#06#` | Show IMEI |

## Tools
| Tool | Use |
|---|---|
| **StopNCII.org** | Block intimate images shared without consent (18+); the photo stays on the phone. Under 18 → **takeitdown.ncmec.org** |
| **haveibeenpwned.com** | Has this email/phone been in a data breach? |
| **virustotal.com** | Scan file / URL / APK / IP. Don't upload private files — search the SHA-256 hash |
| **MobiArmour** | Mobile security ⚠️ confirm the publisher |
| **Sanchar Saathi** | Chakshu (report fraud messages) · TAFCOP (SIMs on your ID) · CEIR (block stolen phone) |
| urlscan.io · who.is · Wayback · Google Lens | Check a link · domain age · deleted pages · photo origin |

## Phone compromised?
`##002#` → change bank/UPI PINs → block cards → remove unknown apps with SMS/Accessibility access → check WhatsApp linked devices.

## Law ⚠️
IT Act 66C identity theft · 66D impersonation cheating · 66E private images · 67/67A obscene content · BNS 318 cheating
