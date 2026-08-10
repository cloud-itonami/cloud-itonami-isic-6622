# Business Model: Activities of insurance agents and brokers

## Classification

- Repository: `cloud-itonami-isic-6622`
- ISIC Rev.5: `6622`
- Activity: independent insurance agency and brokerage -- policy placement, quote comparison, and commission-based intermediation between customer and insurer
- Social impact: financial inclusion, data sovereignty, transparent audit

## Customer

- independent insurance agents and brokers
- cooperative agent networks
- community insurance-access programs

## Offer

- customer intake and needs assessment
- quote comparison and placement proposal
- commission booking
- policy-servicing handoff
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per book-of-business
- support: monthly retainer with SLA
- migration: import from an incumbent agency-management system
- placement-volume fee

| Package | Customer | Price shape |
|---|---|---|
| Managed Starter | one independent agency/brokerage or cooperative agent network, 5-20 producers | ¥30,000/月 flat |

**Market-anchored (2026-08-10)**: benchmarked against 6 real competitor
products, converted at ~¥150/$ for the assumed customer above (8 active
seats). **Only 2 publish real numbers on their own site, and both are
American.**

- **AgencyZoom** (vendor-published): Essential $149/month, Growth
  $199/month, Pro $349/month — each "up to 7 users", 20% annual discount,
  14-day trial — <https://www.agencyzoom.com/pricing>. ≈ **¥22,350 /
  ¥29,850 / ¥52,350 per month** including 7 seats.
- **Insureio** (vendor-published): Basic "$25 billed monthly", Marketing
  "$50", Agency Management "$50", Marketing & Agency Management "$75", plus
  Team View "$5 per team member" — <https://insureio.com/pricing/>. At 8
  members: $75 + 8 x $5 = $115/月 ≈ **¥17,250/月**.
- **NowCerts / Momentum AMS**: **the vendor's own pricing page shows no
  amounts.** A third-party listing reports Essentials $99/mo (1 user),
  Professional $169 (2 users), Business $349 (5 users) and $45/mo per
  additional user — <https://www.g2.com/products/momentum-amp-formerly-nowcerts/pricing>.
  At 8 seats that third-party figure would be $484/月 ≈ ¥72,600/月, but it is
  **an aggregator's number, not a vendor disclosure, and it is not used as an
  anchor here.**
- **AgencyBloc**: **price not disclosed** — the pricing page carries no
  amounts, only "Request Customized Pricing Info" and the note that
  "Commissions+ Packages are based on volume of transactions" —
  <https://www.agencybloc.com/pricing/>.
- **hokan®** (the leading Japanese insurance-agency system): **price not
  disclosed** — the fee field reads only 「お問い合わせください。」 —
  <https://www.aspicjapan.org/asu/service/18489>.
- **Japanese market as a whole**: a comparison article listing 19 Japanese
  insurance-agency systems states a setup/monthly figure for **none of the
  19**, offering only a general "月額5,000円から" market remark —
  <https://boxil.jp/mag/a9593/>.

That last observation is the reason the confidence attached to this anchor is
**medium**: **the Japanese side of this market discloses essentially nothing —
hokan included — and a 19-product comparison contains not one published
price**, so the band above is measured almost entirely on US products sold to
US agencies, and the Japanese buyer's actual reference point cannot be
measured from public sources.

**¥30,000/月 sits in the lower third of the measured band**
(¥17,250-52,350/月 across vendor-published figures). It is below the AMS
tiers, including AgencyZoom Essential (¥22,350/月 for 7 seats) in scope if not
in price, because this actor is not an agency management system: it holds no
customer/policy book of record, no quoting integrations, no commission
accounting or reconciliation, and no marketing automation. It stops at
intake, jurisdiction licensing/commission-cap checklisting,
conflict-of-interest screening, and two proposal gates
(`:placement/bind` and `:commission/book` are never auto at any phase). It is
above the Insureio floor (¥17,250/月) because that tier is a thin CRM, and
because the one thing that makes "independent" intermediary advice credible —
structurally blocking a placement steered toward whichever insurer pays the
broker most, a placement on fewer than two compared quotes, or a commission
above the jurisdiction's recorded cap — is sold by none of the six. ¥30,000
also lands where third-party reporting places the Japanese floor for this
category, which keeps the number explainable to a Japanese buyer even though
no Japanese vendor publishes one. The figure is derived only from the
measurements above; it is **not** carried over from the ¥50,000-150,000/月
range used by the HR/recruiting/CRM-anchored flagships, whose per-seat
comparators have no evidenced relationship to agency/brokerage pricing.

**Subscribe (2026-08-10)**: a live Stripe Payment Link for the Managed
Starter tier (¥30,000/月 flat) is available now —
[**subscribe to Managed Starter**](https://buy.stripe.com/8x2cN6aeH10Y6Hm6TUeEo05).
This is a no-code Stripe-hosted checkout (Gftd Japan 株式会社, JPY); nothing
in this repo's actor code changed. Managed-tenant setup is manual today —
there is no automated onboarding. **No agency or brokerage has subscribed to
this tier yet — this is a live, working checkout with zero paid tenants, not
a claim of existing revenue.**

## Trust Controls

- no policy is placed or bound on a customer's behalf, and no commission
  is booked, without human sign-off
- an undisclosed conflict of interest on the assigned broker, a
  placement based on fewer than two compared quotes, a fabricated
  jurisdiction licensing citation, a commission rate exceeding the
  jurisdiction's own recorded cap, or a double-booking of an
  already-booked commission -- each forces a hold, not an override
- every intake, assessment, screening, binding and booking path is
  auditable
- emergency manual override paths remain outside LLM control
