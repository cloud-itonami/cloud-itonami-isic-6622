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
