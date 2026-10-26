# Spec: Card Authorization Pre-check

**Owner:** human (architect). AI agents may read this file but never change it.
**Version:** 1.0

## Purpose

Before an authorization request (ISO 8583 MTI 0100) is sent to the issuer host, a
pre-check rejects requests that can be declined locally. The pre-check returns an
ISO 8583 response code (field 39).

## Contract

Implement `dev.aiverify.contract.AuthorizationPrecheck`:

```java
String check(AuthRequest request, LocalDate transactionDate);
```

`AuthRequest` fields:

| Field | ISO 8583 | Type | Notes |
| --- | --- | --- | --- |
| `pan` | DE 2 | String | Primary account number |
| `expiry` | DE 14 | String | Expiration date, format `YYMM` |
| `amountMinor` | DE 4 | long | Transaction amount in minor units (cents) |
| `availableMinor` | — | long | Available balance in minor units |

## Acceptance criteria

Rules are evaluated **in this order**; the first rule that fails decides the code.

| ID | Rule | Response code |
| --- | --- | --- |
| AC-01 | `pan`, `expiry` must not be null. `expiry` must be exactly 4 digits, and its month (`MM`) must be 01–12. | `30` Format error |
| AC-02 | `pan` must be 13–19 digits (digits only) and pass the Luhn check. | `14` Invalid card number |
| AC-03 | The card is valid **through the last day** of its expiry month. Year `YY` means `20YY`. The card is expired only if `transactionDate` is after the last day of the expiry month. | `54` Expired card |
| AC-04 | `amountMinor` must be greater than 0. | `13` Invalid amount |
| AC-05 | `amountMinor` must not exceed `availableMinor`. An amount **equal to** the available balance is allowed. | `51` Insufficient funds |
| AC-06 | Otherwise approve. | `00` Approved |

### Notes on dates (AC-03)

- `transactionDate` is ISO 8583 DE 13, *Date, local transaction* (`MMDD`, no time part). It is the acquirer's
  local date. Never re-derive it from the issuer's clock or UTC: near midnight at month end that would turn a valid
  card into an expired one (or the reverse). There is no "last second" to test; the boundary is the date.
- DE 13 is a conditional field and may be missing. A missing date is a format error (`30`), never a crash (NF-05).
- Boundaries the golden set must cover: the first day of the expiry month (G-34), any day in it (G-14), its last
  day (G-15), the day after (G-16), February in normal and leap years (G-18, G-19), and the year rollover for a
  December card (G-35, G-36).

## Non-functional rules (checked by the policy gate)

| ID | Rule |
| --- | --- |
| NF-01 | The PAN must never be written to logs or the console (PCI DSS). |
| NF-02 | No file, network, process, reflection, or `System.exit` access. |
| NF-03 | Only `java.time`, `java.util` and the contract package may be imported. |
| NF-04 | No hard-coded secrets or credentials. |
| NF-05 | No input may make `check` throw. A null `request` or null `transactionDate` returns `30` Format error. (Gate baseline rule; the gate adds a null guard automatically when this is the only failure.) |
