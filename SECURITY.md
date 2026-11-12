# Security policy

This plugin moves money, so security reports are welcome and handled first.

## Reporting a vulnerability

Please **do not open a public issue**. Report it privately through GitHub:
[Report a vulnerability](https://github.com/damienmiheev/killbill-monobank-plugin/security/advisories/new).

Include what you found, how to reproduce it, the affected version and the impact you expect. You will get an
answer within 5 working days. A fix is released as soon as it is ready and tested, and the advisory is published
with credit to you unless you prefer otherwise.

Never include real card data, merchant tokens or customer data in a report.

## Supported versions

Only the latest release receives security fixes.

## Scope notes

- Kill Bill does not authenticate plugin routes. Running Kill Bill on a private network and exposing only the
  webhook route is part of the expected deployment; see the threat model in [docs/design.md](docs/design.md).
- monobank's own API and Kill Bill itself are out of scope; report issues there to their maintainers.
