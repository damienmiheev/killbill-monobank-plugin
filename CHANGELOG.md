# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Bundle skeleton for Kill Bill 0.24: activator, system and tenant settings (with `env:` secrets; a tenant's
  merchant-token alias must be bound to its API key), schema check on start (tables, columns and the unique indexes
  money safety relies on), health check, PostgreSQL schema with database-level guards against a second unresolved
  charge or refund, money conversion by each currency's ISO 4217 exponent.
- Payment operations are refused with `NOT_IMPLEMENTED` until they land.

[Unreleased]: https://github.com/damienmiheev/killbill-monobank-plugin/commits/main
