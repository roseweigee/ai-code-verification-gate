The quality gate tests the business rules on their own, so they must be in one plain Java class:

1. Class `AuthorizationRules` in the package `{base}.rules` (for example `com.card.rules.AuthorizationRules`).
2. Method: `public String check(String pan, String expiry, long amountMinor, long availableMinor, java.time.LocalDate transactionDate)`.
   It returns the result code of the first matching rule above, for example `"00"` or `"54"`.
   A missing field is passed as `null` (`pan`, `expiry`, `transactionDate`).
3. No Spring or Lombok annotations, no database, no logging. Only `java.time`, `java.util` and `java.util.regex` imports.
4. The service implementation calls `AuthorizationRules.check(...)`; it must not repeat the rules.
