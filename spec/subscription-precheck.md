# Spec: Fund Subscription Suitability Pre-check

**Owner:** human (architect). AI agents may read this file but never change it.
**Version:** 1.0
**Scenario:** wealth and asset management. Same gates as the card-payment scenario; only this spec, the
contract and the golden tests change.

## Purpose

Before a client's fund subscription order is sent to the fund house or transfer agent, a pre-check rejects
orders that must not go through: an out-of-date risk assessment (KYC), a fund riskier than the client's
risk profile (suitability), an amount below the fund's minimum, or an order that would concentrate too
much of the client's portfolio in one fund. The pre-check returns one result code.

The result codes below are this project's own codes, not an industry standard.

## Contract

Implement `dev.aiverify.contract.SubscriptionPrecheck`:

```java
String check(SubscriptionRequest request, LocalDate tradeDate);
```

`SubscriptionRequest` fields:

| Field | Type | Notes |
| --- | --- | --- |
| `clientRiskLevel` | String | The client's KYC risk profile: `C1` (most conservative) to `C5` (most aggressive) |
| `fundRiskLevel` | String | The fund's risk rating: `RR1` (lowest) to `RR5` (highest) |
| `kycAssessedOn` | LocalDate | Date the client's KYC risk assessment was completed |
| `amountMinor` | long | Order amount in minor units (1/100 of the fund currency) |
| `minimumMinor` | long | The fund's minimum subscription amount, in minor units |
| `holdingMinor` | long | The client's current holding in this fund, in minor units |
| `portfolioMinor` | long | The client's total portfolio value, **including** `holdingMinor`, in minor units |

`tradeDate` is the business date of the order.

## Acceptance criteria

Rules are evaluated **in this order**; the first rule that fails decides the code.

| ID | Rule | Result code |
| --- | --- | --- |
| AC-01 | `clientRiskLevel`, `fundRiskLevel` and `kycAssessedOn` must not be null. `clientRiskLevel` must be exactly one of `C1` `C2` `C3` `C4` `C5`, and `fundRiskLevel` exactly one of `RR1` `RR2` `RR3` `RR4` `RR5` (upper case, no spaces, no leading zeros, no signs). | `FMT` Format error |
| AC-02 | A KYC assessment is valid for one year, **up to and including** the same calendar date one year later (`kycAssessedOn.plusYears(1)`; an assessment on 29 February is valid through 28 February of the next year). It has expired only if `tradeDate` is after that date. | `KYC` Assessment expired |
| AC-03 | The fund's risk number must not exceed the client's risk number: a `C3` client may buy `RR1` to `RR3`. A fund **equal to** the client's level is allowed. | `UNS` Unsuitable |
| AC-04 | `amountMinor` must be greater than 0 and at least `minimumMinor`. An amount **equal to** the minimum is allowed. | `AMT` Invalid amount |
| AC-05 | Concentration: after the order, the holding in this fund (`holdingMinor + amountMinor`) must not exceed 30% of the portfolio after the order (`portfolioMinor + amountMinor`). **Exactly** 30% is allowed. Money is compared exactly, in whole minor units; never with floating point. | `CON` Concentration limit |
| AC-06 | Otherwise accept. | `OK` Accepted |

### Notes

- Suitability (AC-03) and an up-to-date risk assessment (AC-02) are what a regulator asks to see for every
  order: the pre-check is the control, and the audit log is the evidence.
- Boundaries the golden set must cover: a fund equal to the client's level (G-08 to G-10), the KYC anniversary
  and the day after (G-04, G-05), a 29 February assessment (G-06, G-07), an amount equal to the minimum (G-15), a fund with no minimum (G-13, G-31),
  exactly 30% after the order and one minor unit more (G-19, G-20).

## Non-functional rules (checked by the policy gate)

| ID | Rule |
| --- | --- |
| NF-01 | No client data may be written to logs or the console: the code must not print or log at all. |
| NF-02 | No file, network, process, reflection, or `System.exit` access. |
| NF-03 | Only `java.time`, `java.util` and the contract package may be imported. |
| NF-04 | No hard-coded secrets or credentials. |
| NF-05 | No input may make `check` throw. A null `request` or null `tradeDate` returns `FMT`. (Gate baseline rule; the gate adds a null guard automatically when this is the only failure.) |
